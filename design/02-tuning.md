# Rootstock Domain 02 — The Tuning System

**Status:** Design complete, ready to implement. **Revision 4 — the missed binding-decision sweep, plus the independent expert review of 2026-08-07.**
**Target:** WPILib 2026 (`edu.wpi.first.*`, Java 17) with a mechanical 2027 port path (`org.wpilib.*`, Java 25) · **AdvantageKit 26.0.2 REQUIRED**
**Owner package roots:** `org.rootstock.control` (the canonical control vocabulary — `Gains`, `GainId`, `GainSink`, `Controllers`, `TuningTarget`, `MechanismArchetype`, `PositionReference`, `TravelLimits`, `PlantPrior`, `SafetyEnvelope`, `TuningSupervisor`), `org.rootstock.tuning`, `org.rootstock.tuning.sysid`, `org.rootstock.tuning.wizard`, `org.rootstock.tuning.diagnostics`, `org.rootstock.tuning.persist`, `org.rootstock.tuning.ui`, plus the HAL-free solver core in `org.rootstock.pure.solvers`.
**License:** BSD-3-Clause ([`LICENSE`](../LICENSE)).

> ### ⚠ Revision 4 — this document was **missed** by the 2026-08-07 four-decision sweep
>
> [`DESIGN.md`](../DESIGN.md) §16 item 4 recorded that the sweep applied the four binding maintainer decisions "to all ten documents." **Its own document list omitted `design/02-tuning.md`, and the omission showed.** As of revision 3 this document still specified a twelve-component `Gains`, a `LoopLocation` enum that [`DESIGN.md`](../DESIGN.md) §5.1 **D5** deletes, a `TuningTarget` in the wrong package with a surface incompatible with the one §11b compiles in CI, a `MechanismUnits` enum that **D3** renamed, a `GravityType` that **D2a** deleted, `GainStore`/`GainsExporter` where the master and [`ROADMAP.md`](../ROADMAP.md) say `TunedValueStore`/`ValueExporter`, a per-tunable NT polling model that **D11a** superseded, "AdvantageKit — optional, reflectively detected" three paragraphs after "AdvantageKit REQUIRED", a nine-domain numbering scheme that has not existed for two revisions, and a flagship end-to-end example written against a `LinearMechanism` API that appears in no document. `[SUPERSEDED-NAME]`
>
> **Revision 4 runs that sweep, and applies the review's other twenty-three findings.** [`DESIGN.md`](../DESIGN.md) §5 (decisions **D1**–**D30**) is authoritative; where this document previously disagreed, it now conforms, and every conformance change is named in the table below. **Cross-doc action:** `DESIGN.md` §16 item 4 must be corrected to admit design/02 was missed, and the items in §19 below must be added to §16 item 5.
>
> No tuning mathematics, no safety condition and no teaching content was *removed*. Six numbers were **wrong** and are corrected with their derivations shown (§4.2's π-factor geometry, §6.5's `kGprior`, §7.1's supervisor guard, §9.2.2's panel, §11.4's gains file, §11.5c's report).

### Revision 4 — what changed and why

| # | Change | Section | Was |
|---|---|---|---|
| A | **`Gains` is exactly seven doubles** `(kP,kI,kD,kS,kV,kA,kG)`, all volts-per-SI, named-field construction only. `GravityType`, `ProfileConstraints`, `toleranceSi`, `iZone` and `iMaxVolts` are **deleted from `Gains`** and live on `ControlConfig` (**D1a/D1b/D2/D2a**). `Gains.zero()` is deleted; `Gains.UNTUNED` is the placeholder (**D2c**). | §3.2, §5.5, §11.4, §12.3, §16 | A twelve-component record with `withGravity`, `withProfile`, `withTolerance`, `withIntegral` and a twelve-value `GainId` |
| B | **`TuningTarget` moves to `org.rootstock.control`** with the exact surface [`DESIGN.md`](../DESIGN.md) §11b compiles (`tuningName()`, `siDomain()`, `measuredSi()`, `velocitySi()`, `gains()`, `gainSink()`, `travelLimits()`, `plantPrior()`) (**D8**) | §3.1, §14.4, App. A | `org.rootstock.tuning.TuningTarget` with `name()`, `units()`, `getPosition()`, `applyGains(Gains)` |
| C | **`LoopLocation` is deleted.** `[SUPERSEDED-NAME]` Tuning calls `ControlLocation.runsOnMotor()` (**D5**). §9.3.1's contrary "decision" and OQ#13 are marked ANSWERED. | §3.1, §9.2.1, §9.3.1, §18 | Two names for one axis, both taught to the student in one error message |
| D | **`MechanismUnits`-the-enum → `org.rootstock.units.SiDomain {LINEAR_METERS, ROTATIONAL_RADIANS}`** (**D3**). CORE keeps `MechanismUnits` as the converter. | throughout | A name collision with CORE's converter class |
| E | **`GravityType` → `org.rootstock.control.GravityMode {NONE, CONSTANT, COSINE}`**, derived from the `Axis` (**D2a**) | §3.2, §4.2, §9.3.1 | `Gains.GravityType {NONE, ELEVATOR_STATIC, ARM_COSINE}` |
| F | **`GainStore` → `TunedValueStore`; `GainsExporter` → `ValueExporter`** `[SUPERSEDED-NAME]` — the names [`DESIGN.md`](../DESIGN.md) §12.1a and [`ROADMAP.md`](../ROADMAP.md) M6 gate on | §1.1, §2.2, §11, App. A | Two names for one class pair, zero matches for the canonical ones |
| G | **AdvantageKit is a REQUIRED, compile-time dependency** in §2.3, not "optional, reflectively detected". `Nt4TunableTransport` is gone from Appendix A. `[SUPERSEDED-NAME]` | §2.3, §5.6, App. A | A dependency floor stated backwards in the one document a tuning-first adopter reads |
| H | **One `NetworkTableListenerPoller`, one `readQueue()` per loop, one `LoggableInputs` struct** (**D11a**) — not one `entry.get()` per tunable | §5.3, §5.6, §5.8 | 144 JNI calls per loop, and a replay story that did not survive contact with the poller |
| I | **Domain numbers → filenames.** There is no "domain 03", no "domain 07", and no "bring-up domain (09)". | §2.1, §9.3.1, §18 | A reader following "domain 03" landed in the vision document |
| J | **Config-reachable validation is collected, never thrown.** `TravelLimits` and `PlantPrior` return `List<ConfigError>` from a pure `validate(owner)`; `TuningRegistry.register` surfaces them through the same three-tier pipeline and SAFE_MODE that `design/01` §5.6 owns. | §3.1, §14.1, §16.3 | Compact constructors that throw — the `ExceptionInInitializerError`-at-11pm failure `DESIGN.md` §14 row 7 declares structurally unrepresentable |
| K | **§4.2's headline conversion example had a π-factor geometry error** (`0.0879 m` where the stated geometry gives `0.2794 m`). Recomputed, with the derivation printed, against **one** plant shared with `design/01` §5.4. | §4.2, §5.5, §11.4, §11.5c | Every downstream number in the flagship unit-correctness demo derived from a figure that was `0.2794/π` |
| L | **`kGprior` divided by `motorCount` while prescribing an n-motor `DCMotor`**, making the prior n× low and exhausting the kG bracket on healthy two-motor mechanisms. Corrected, verified against WPILib's `DCMotor` source. | §6.5, §8.4, §16.2 | `kGprior = tau / (G * Kt/R * motorCount)` |
| M | **`SafetyEnvelope.derive`'s guard degenerated for any `softMargin ≥ 5%` of travel.** Now `guard = softMargin + max(0.03·range, floor)`, which is strictly greater than the margin for every legal margin, with the proof and three new test rows. | §7.1, §16.2 | `guard = max(softMargin, 0.05·range)` — the supervisor band **equaled** the device soft-limit band at 5% and above |
| N | **The kG drift probe could not see drift in brake mode or through gearbox stiction**, and silently set `kG = 0` on exactly the archetypes that need it. Now: idle-mode read-back, a supervised coast borrow, and a **breakaway-asymmetry** fallback that measures the sign in brake mode. The `kG = 0` shortcut is reserved for archetypes whose pre-flight gravity test already passed. | §8.4, §8.5, §3.1 | A 0.5 s zero-voltage probe on a brake-mode arm, followed by kS coming out ten times too large |
| O | **`abort()` neutralled gravity mechanisms**, contradicting the taper note and `abortProbe` in the same section. Pre-flight now **requires** BRAKE for `hasGravity()` archetypes, and the coast borrow restores BRAKE *before* neutralling. | §7.3, §7.5, §8.7, §8.8 | A coast-mode arm dropped onto its hard stop on every `ENABLE_RELEASED` |
| P | **`probe()` and `recentre()` were blocking `while` loops** in a codebase that bans threads and drives steps from `periodic()`, with no `supervisor.check()` and no timeout. Respecified as an explicit sub-state machine. | §8.4, §16.2 | Pseudocode that could not be implemented on the 20 ms main loop |
| Q | **§14.1's flagship example targeted a `LinearMechanism` API that exists in no document** `[SUPERSEDED-NAME]` and seeded `Gains.zero()`. Rewritten against `PositionConfig`/`PositionMechanism` with `Gains.UNTUNED`, on `design/01` §5.4's exact geometry. §14.2 rewritten to the §11c `RootstockLifecycle` + `RootstockRegistry.addAll` shape. | §14 | A config language that differed from the README's and `DESIGN.md`'s in builder, type names and placeholder contract |
| R | **Volatile-API confinement** (`DESIGN.md` §16 item 3): `DriverStation` → `MatchContext`, `Filesystem` → `Platform`, `Timer` → `Clock`, `Alert` → `Alerts` with a mandatory `MatchImpact` (**D10**). `TuningRegistry`'s rate-gated half becomes a `SliceScheduler` slice; its poller drain is `LifecycleHook` priority 30. | §2.1, §5.2, §7.3, §11.2, §14.2 | Direct reads of four APIs domain 06's ArchUnit rules forbid, and a free-standing `periodic()` |
| S | **`AbortReason` is exactly twelve values**, matching [`ROADMAP.md`](../ROADMAP.md) M6's "all 12 abort conditions": eleven checked every loop by `check()` plus `UNSTABLE_RESPONSE`, raised by the refinement loop. Fit failures moved to their own `FitFailure` enum. | §7.2, §6.4, §16.2 | Eleven loop conditions, plus two fit failures and one refinement abort smuggled into the same enum — a count nobody could reconcile |
| T | **§13.11's practice table split.** kS/kV/kA/kG are checkable answers; kP/kD are WPILib's tutorial choices, and the wizard's LQR result legitimately differs. | §13.11 | "The answer you are looking for" next to a kP the wizard does not produce |

#### The `[SUPERSEDED-NAME]` marker — why `LoopLocation` and four other deleted names still appear in this document

The table above deletes five type names, and this document deliberately keeps mentioning them: a reader arriving from revision 3, or from another team's fork of it, needs to be told *"that type is gone, and here is what replaced it."* **Deleting the mentions would delete the record of the correction**, which is the one thing [`DESIGN.md`](../DESIGN.md) §16 exists to prevent. But it also means the obvious gate — *"grep for the deleted name, expect zero"* — can never pass here, and a gate that red-flags a correct document on day one gets disabled, after which it gates nothing.

> **So every surviving mention carries the literal marker `[SUPERSEDED-NAME]`, and the gate is carved out around it.** The five carved names are `LoopLocation` (→ `ControlLocation.runsOnMotor()`, **D5**), `GainStore` (→ `TunedValueStore`), `GainsExporter` (→ `ValueExporter`), `Nt4TunableTransport` (deleted by maintainer decision 3) and `LinearMechanism` (→ `PositionMechanism`/`PositionConfig`). **Marker scope** is defined identically to the `[INTENTIONAL-…]` carve-out that [`design/01`](01-core-mechanisms.md) §4.2 owns for its deliberately-wrong gear-ratio examples *(the digits are omitted here on purpose: writing that marker's full literal in this file would add a hit to `design/01`'s repo-wide gate for a value this document does not contain)*: the same line; or — where an inline marker would corrupt sample output or a table row — any line of the same **fenced code block**, the same **block-quote**, or the same **markdown table**, plus the block-quote immediately preceding a fence.
>
> **The gate, both directions** ([`DESIGN.md`](../DESIGN.md) §16 item 4(f) is the owning statement):
> 1. `grep -rn "LoopLocation\|GainStore\|GainsExporter\|Nt4TunableTransport\|LinearMechanism" design/` returns **zero outside `[SUPERSEDED-NAME]` marker scope**.
> 2. **Reverse:** every `[SUPERSEDED-NAME]` marker has one of those five literals — `LoopLocation`, `GainStore`, `GainsExporter`, `Nt4TunableTransport`, `LinearMechanism` — in its scope, so a sweep that over-applies the marker fails too.
> 3. A struck-through (`~~name~~`) or explicitly negated occurrence **still needs the marker** — the marker, not the prose, is what the grep can see.
> 4. **The reverse gate must be defined over the *union* of every carved name set, not over these five.** [`DESIGN.md`](../DESIGN.md) §16 items **6** and **7** carve a *different* name set with the *same* `[SUPERSEDED-NAME]` literal — `getGyroHeading`, `VisionObservation`, `RootstockAlerts.` — in `design/03`, `design/04`, `design/05` and `DESIGN.md`. Those markers are not placed yet. **When they are, a reverse gate written against only the five names above — `LoopLocation`, `GainStore`, `GainsExporter`, `Nt4TunableTransport`, `LinearMechanism` — will report every one of them as an orphan** and red-flag correct work on the day it lands, which is the exact failure mode this whole marker mechanism was invented to avoid. Stated here rather than discovered later: one marker literal, one union of carved names, one reverse gate over that union.
>
> **Run by hand 2026-08-08 and both directions PASS.** Across all of `design/`, **21** lines contain one of the five literals and **21** lines carry a marker, and they are the *same twenty-one*: every occurrence is marked **on its own line**, which is the strictest reading of scope, so the fenced-block / table / block-quote clauses are not being leaned on by any current site. Sixteen of the twenty-one are the supersession sites themselves — [`DESIGN.md`](../DESIGN.md) §16 item 4(f) enumerates them — and five are this definition block, which names the literals in order to forbid them and therefore needs the carve-out like anything else. No occurrence is unmarked; no marker is orphaned; `design/01` and `design/03`–`design/06` contain none of the five literals at all, so the repo-wide grep is satisfied by this file alone. It becomes a CI job at **M24** alongside the `design/01` §4.2 gate it is modeled on.

### Revision 3 — what the four maintainer decisions did to this document

No tuning mathematics, no safety condition and no teaching content changed. Scope, packaging and the dependency floor did:

| # | Decision | Effect here |
|---|---|---|
| A | **1 — one release, v0.1, containing everything** | This domain is built across **M6** (tunables + persistence + Elastic, 1.1 pw), **M7** (wizard core + `ELEVATOR`/`FLYWHEEL`, 3.0 pw) and **M13** (`ARM`/`TURRET`/`STEER`/`DRIVE_VELOCITY` + `MechanicalHealthCheck` + step-response refinement, 3.5 pw) — **all one release.** [`ROADMAP.md`](../ROADMAP.md) is authoritative for dates. **The honest number: at solo pace M7 completes 2027-04-24, one week AFTER the 2027 season ends**; at +2 committers it completes 2026-12-15, before kickoff. That gap is the single strongest argument in the whole plan for adding a committer, and it lands squarely on this document, because the wizard is the reason the project exists. The optional web UI (§12, §18 q5) is in **no** milestone and is therefore outside v0.1. |
| B | **2 — `RootstockTemplate` is the front door** | `src/main/deploy/rootstock/` and `gains.json` (schema-stamped `rootstock.gains/1`) ship **inside the template**, pre-created, rather than being something a team is told to make. The generated Elastic tuning layout ships there too. |
| C | **3 — AdvantageKit is REQUIRED** | §5.6's reflective transport probe is deleted; `AdvantageKitTunableTransport` is the only implementation and replay-safe tunables are a **guarantee**. **The "No AdvantageKit dependency" advantage over 6328's `LoggedTunableNumber` (§5.1) is WITHDRAWN** — we now have exactly the dependency they do, and it must not be claimed as differentiation anywhere. The remaining differentiators (one poller, FMS default-deny in constant time, the `/applied` echo, 4-tier persistence, the export path) never rested on it. |
| D | **4 — BSD-3-Clause** | License decided; no "TBD" anywhere. `gains.json` and exported Java carry no license question for a team that vendors them. |

### Revision 2 — what changed and why

Five of these were findings that would have broken a real mechanism or shipped a safety claim that was not true. They are listed first because the failure walk-throughs are the most useful part of this document for anyone implementing it.

| # | Change | Section | Was |
|---|---|---|---|
| 1 | **Motion is impossible outside Test mode**, by a hard throw in `arm()`; the wizard gets its own controller port | §7.3, §7.4 | The wizard's held-enable was the driver's right trigger, live in teleop on any practice field with no FMS |
| 2 | **kG bisection rebuilt**: physics-derived bracket, in-window position guard, aborts as signed measurements, real closed-loop `recentre()`, 18 → 10 iterations | §8.4 | Open-loop 3.6 V on a 1.2 V arm, position guard evaluated only *after* a 0.5 s window, `recentre()` referenced and never defined. It would have slammed a hard stop on iteration 1 |
| 3 | **`arm()` refuses without a trustworthy position reference** — `isHomed()`, the position reference, absolute-vs-rotor agreement, ARM zero re-checked at arm time | §3.1, §7.3.1 | Six preconditions, none of which was "the mechanism knows where it is" |
| 4 | **`getFeedbackVolts()` added to the SPI**; the steady-state rules gate on it and say so when it is absent | §3.1, §9.3.1 | `residualVolts` was not computable on an on-motor loop — the recommended default — so the whole kS/kG diagnosis was dead code falling through to `kP *= 1.4` |
| 5 | **Supervisor band derived from travel, not from the margin**; `TravelLimits` rejects a margin under 2% of travel | §3.1, §7.1 | With the default `softMargin = 0` the supervisor band equaled the device soft limits and the documented guarantee was false |
| 6 | **Sim gate tests the envelope, not the fit**: 9 Monte-Carlo perturbed runs, promotion on containment; demoted from headline safety property | §7.6 | In sim the plant *is* `PlantPrior`, so the gate could not fail — pure friction with an escape hatch |
| 7 | **`PredictStep`** — the wizard asks, scores, and reports `Predictions: n/m`; `LqrSuggestStep` shows `wn`/`zeta` instead of an oracle number; `Lessons.WHAT_THE_SLIDERS_DO` | §8.4, §9.2.2, §13.5a | Eleven steps of read-then-watch-then-press-A, with no way for the wizard or a mentor to tell learning from button-mashing |
| 8 | **`TuningRecipe.express()`** — identification only, ~90 s, teaching mode default in sim only | §8.12 | Eight minutes x four mechanisms x one shared robot, raised as OQ#10 and not answered |
| 9 | **kG sign is measured, not assumed** — a zero-voltage drift probe opens the bisection | §6.5, §8.4 | `[0, 0.6*V]` bracket and `kG >= 0` hard-coded "positive position = up"; a downward-positive wrist got kG = 0 |
| 10 | `GainId.IZONE`/`IMAX_VOLTS` added; `setpoint` and the LQR sliders moved out of `/Tuning`; `with(KI, v)` substitutes a clamp instead of throwing | §3.2, §5.5 | The published schema had three topics `Gains` could not round-trip, and the review panel's first kI edit threw mid-session |

> **Revision 4 note on row 10.** The *problem* row 10 fixed is real and is still fixed — but **D1a** moves `iZone`/`iMaxVolts` off `Gains` entirely, onto `ControlConfig.integral(kI, iZone, iMaxVolts)`. The `GainId` enum is back to seven values, the schema is still generated rather than typed, and the windup guard still exists — it just lives where the policy lives. See §3.2 and §5.5.

Everything above is pinned by a named test in §16. Where a fix depends on a claim we could not verify from a vendor javadoc, it is marked `[UNVERIFIED]` inline and repeated in Appendix B.

---

## 1. Scope & Responsibilities

This domain owns **everything between "the mechanism moves" and "the mechanism moves correctly."**

### 1.1 In scope — we own these

| # | Responsibility | Deliverable |
|---|---|---|
| 1 | Canonical gain type and unit system | `org.rootstock.control.Gains` — seven doubles, volts-per-SI, one definition for every vendor |
| 2 | Live-tunable values over NetworkTables | `RootstockTunable`, `TunableDouble`, `TunableBoolean`, `TunableGains`, `TuningRegistry` |
| 3 | Vendor write-through of gains | `GainSink` SPI + change-gated, rate-limited apply |
| 4 | On-robot system identification | `SysIdSweep` (wraps WPILib `SysIdRoutine`), `FeedforwardRegression` (streaming OLS) |
| 5 | Assisted feedback-gain derivation | `FeedbackDesigner` (LQR from measured kV/kA, over the HAL-free `LqrDesign` core) + bounded step-response refinement |
| 6 | Guided, teaching, on-robot tuning wizard | `TuningWizard`, `TuningRecipe`, `TuningStep`, six built-in recipes |
| 7 | Response classification and plain-language coaching | `StepResponseAnalyzer`, `ResponseVerdict`, `Coach` |
| 8 | Safety supervision of every actuating routine | `TuningSupervisor`, `SafetyEnvelope`, `AbortReason` |
| 9 | Gain persistence and source write-back | `TunedValueStore`, `gains.json`, `ValueExporter` (paste-ready Java) |
| 10 | The tuning UI surface and its NT schema | `TunerPublisher`, shipped `elastic-tuning-layout.json` |
| 11 | Teaching content | `Lessons` — real, written explanations shipped as data, published to NT |
| 12 | Mechanical pre-flight that must pass before tuning | `MechanicalHealthCheck` (backlash, asymmetric friction, encoder slip) — **stays in tuning per D23**, because it commands raw voltage and must go through `TuningSupervisor` |
| 13 | **Formative assessment** — the wizard asks the student to predict, then scores it | `PredictStep`, `/RootstockTuner/predict/*`, a `Predictions: 7/9` line in the report |
| 14 | A fast path for the fourth mechanism of the day | `TuningRecipe.express()` — identification only, one narration screen, ~90 s |

### 1.2 Explicitly out of scope for this domain

- **Building the mechanism.** We consume a `TuningTarget` (§3.1); [`design/01-core-mechanisms.md`](01-core-mechanisms.md) constructs it.
- **Swerve module bring-up** (invert/offset discovery). That belongs to [`design/01-core-mechanisms.md`](01-core-mechanisms.md) (`HomingStrategy`, `describe()`, the `rotorPerSensor × sensorPerOutput == reduction` identity rule) and [`design/05-drivetrain-auto.md`](05-drivetrain-auto.md) (`DriveSelfCheck`, `RootstockCharacterization`). We *consume* a correctly-brought-up module and tune its gains.
- **Logging and replay infrastructure.** AdvantageKit, owned by [`design/04-telemetry-replay-viz.md`](04-telemetry-replay-viz.md). We publish; we do not own the logger.
- **Plotting applications.** AdvantageScope. We publish plot topics; we do not draw them.
- **Dashboards.** Elastic. We ship a layout JSON; we do not write a dashboard.
- **The control loops themselves.** `PIDController`, `ProfiledPIDController`, `ArmFeedforward`, `ElevatorFeedforward`, `SimpleMotorFeedforward`, `TrapezoidProfile`, `ExponentialProfile`, Phoenix 6 `Slot0Configs`, REVLib `ClosedLoopConfig`. We compute the *numbers that go in them*.

### 1.3 The one-sentence pitch

> Every other FRC tuning system shows a student **where the knobs are**. Rootstock tells them **which knob to turn next, why, and by how much** — and it does the arithmetic that a redeploy loop cannot do.

This is the headline feature and it is a genuinely unoccupied niche. The only FRC repo advertising an auto-tuner (`Prosper-FRC/utility-main-autoPIDTuner`) has a README and **no `src` directory** (web-tuning dossier, painPoints). YAMS ships Live Tuning but it is a manual slider panel. FrcCatalyst ships `TunableGains.checkAndApply()` but no recipe. SysId characterizes but does not teach and requires a laptop round trip.

---

## 2. Integration Points

### 2.1 What I need FROM other Rootstock documents

**There is no domain numbering.** The shipped set is `design/01-core-mechanisms.md`, `design/02-tuning.md` (this file), `design/03-vision.md`, `design/04-telemetry-replay-viz.md`, `design/05-drivetrain-auto.md`, `design/06-platform-compday.md`. Revisions 1–3 of this document used a nine-domain scheme in which "03" meant mechanisms and "09" meant a bring-up domain that has never existed; every such reference is replaced below with the file that actually owns the thing.

| From | What I need | Why |
|---|---|---|
| [`design/01`](01-core-mechanisms.md) | Every `Mechanism` implements `org.rootstock.control.TuningTarget` (§3.1) | The tuner is generic; it needs voltage-in / SI-state-out / limits / plant prior |
| [`design/01`](01-core-mechanisms.md) | Mechanisms **consume** `org.rootstock.control.Gains` as their gain type, and expose a `GainSink` that writes through to the vendor | Otherwise tuned values sit on a dashboard next to a controller that ignores them (this is exactly the failure §2.4 records in `0000-XXXX-Robot-Template` at commit `f02b51d`) |
| [`design/01`](01-core-mechanisms.md) | `PlantPrior` inputs (`DCMotor`, `Reduction`, mass or MOI, drum radius or arm length) from the mechanism's own config | Sanity-bounding the fit and generating the sim plant. **D7**: `TuningRegistry.register(target)` asserts `PlantPrior.reduction().rotorPerOutput() == config.reduction().rotorPerOutput()` and refuses the registration with a named `ConfigError` if they disagree |
| [`design/01`](01-core-mechanisms.md) | `TravelLimits` (min, max, soft margin) derived from `PositionLimits`, with device soft limits already configured | `TuningSupervisor` refuses to arm without them (§7.1, §7.3) |
| [`design/01`](01-core-mechanisms.md) | `ControlLocation` per mechanism, **defaulted from the leader's `MotorSpec`** (**D5**) | kP means different things and the refinement loop's dt differs. Tuning asks `controlLocation().runsOnMotor()`; it never asks a team to type it |
| [`design/01`](01-core-mechanisms.md) | Guarantee that encoder direction, gear ratio and zero offset are already correct before a `TuningTarget` is handed to us — enforced by the `rotorPerSensor × sensorPerOutput == reduction` Tier-1 rule and by `describe()` | Tuning a wrong-signed mechanism destroys hardware; we detect it (§7.5, §8.2) but we must not be the primary defense. **Revision 4:** revisions 1–3 assigned this guarantee to a "bring-up domain (09)" that does not exist, which left it unowned — a hole in the safety argument, now closed by naming the real owners |
| [`design/01`](01-core-mechanisms.md) vendor adapters (`hardware/phoenix`, `hardware/rev`, `hardware/generic`) | `GainSink` implementations for Phoenix 6, REVLib, and RIO-side wpimath | Canonical-volts → vendor-native conversion (§4) |
| [`design/04`](04-telemetry-replay-viz.md) | `RootstockLog` and AdvantageKit's `Logger`/`LoggableInputs`/`LogTable` — **not optional; AdvantageKit is a required dependency** (maintainer decision 3) | Replay-safe tunables as a **guarantee**, not as a property of what the team happened to install (§5.6) |
| [`design/05`](05-drivetrain-auto.md) | A `DriveBackend`-backed `TuningTarget` per module for the `DRIVE_VELOCITY` and `STEER` recipes, plus `DriveSelfCheck`'s bring-up verdict | Per-module gains are the point; averaging four modules hides a bad one (§8.10) |
| [`design/06`](06-platform-compday.md) | `Alerts.error/warning/info(group, text, MatchImpact)` returning a `RootstockAlert` (**D10**) — every call site names a `MatchImpact`, no default, no single-argument overload | WPILib's `Alert` API is documented as unstable; we must not depend on it directly, and "does this block a match?" must be answered at the call site |
| [`design/06`](06-platform-compday.md) | `MatchContext.isDiagnostics()`, `isEnabled()`, `isDisabled()`, `isFMSAttached()`; `FmsPolicy.tunablesLocked()` | `MatchContext` is the only class permitted to read `DriverStation` (ArchUnit rule 10). Test-mode gating and FMS default-deny both go through it |
| [`design/06`](06-platform-compday.md) | `Platform.persistentDir()` / `Platform.deployDir()`; `Clock.seconds()` and `Clock.dt()` | `volatileApiIsConfined` is red while this document reads `Filesystem` or `Timer` directly (`DESIGN.md` §16 item 3) |
| [`design/06`](06-platform-compday.md) | `LifecycleHook` priority 30 (drain the poller) and one `SliceScheduler` slice named `Tuning` (metadata, echoes, write-through) | `TuningRegistry` must not own its own rate gate (`DESIGN.md` §16 item 3, §6 runtime diagram) |
| [`design/06`](06-platform-compday.md) | The template's `src/main/deploy/rootstock/` directory and `elastic-tuning-layout.json` served on port 5800 | Persistence baseline + zero-click UI. Decision 2: these ship **inside `RootstockTemplate`**, pre-created |
| [`design/06`](06-platform-compday.md) | `RobotIdentity.current()` returning a `RobotId` (`SIM`, `COMP`, `PRACTICE`, …) | The full *teaching* recipe is the default only in `SIM`; on hardware the wizard offers both and remembers the choice (§8.12) |
| [`design/06`](06-platform-compday.md) | `ControlMap.isPortRegistered(int port)` — the driver/operator controller port registry | `TuningWizard` refuses to share a controller with the driver without an explicit, logged acknowledgment (§7.4.2) |
| [`design/06`](06-platform-compday.md) | `ConfigError` / `Severity` / `Validation.printAll` / SAFE_MODE entry, reached through `RootstockRegistry.addAll` (**D27**) | Config-reachable validation is collected and printed, never thrown (§3.1) |

### 2.2 What I provide TO other documents

- `org.rootstock.control.Gains` — the canonical seven-double gain record every mechanism stores.
- `org.rootstock.control.TuningTarget`, `MechanismArchetype`, `PositionReference`, `TravelLimits`, `PlantPrior`, `GainSink`, `Controllers`, `SafetyEnvelope`, `TuningSupervisor` — all in core (**D8**), so a team implements the seam without depending on the mechanism layer *or* the wizard.
- `TuningRegistry.tunable(...)` — the general-purpose tunable-number primitive, usable by *any* document's code (vision std-dev models, auto-align tolerances, drive speed scalars). **D11**: publication is `/Tuning/<namespace>/<key>` and nowhere else, from an explicit allowlist, never by reflection over field names.
- `StepResponseAnalyzer` — a pure, HAL-free classifier (core in `org.rootstock.pure.solvers`) reusable by `design/06` for "is this mechanism still tuned?" checks between matches.
- `TunedValueStore` — the persistence layer; other documents may register non-gain configuration values, including `Setpoint`s (`DESIGN.md` §5.6: setpoints register under `/Tuning/<Mechanism>/Setpoints/<NAME>` and persist keyed by `RobotId`).
- `TuningSupervisor` — reusable safety envelope for *any* routine that commands raw voltage, including `design/05`'s `RootstockCharacterization`.

### 2.3 Hard external dependencies

**Required — all of them, at compile time.**

- **AdvantageKit 26.0.2 — REQUIRED (maintainer decision 3).** `org.littletonrobotics.junction.Logger`, `org.littletonrobotics.junction.LogTable`, `org.littletonrobotics.junction.inputs.LoggableInputs`. `rootstock` depends on AdvantageKit; there is no classpath without it, no reflective probe, and no NT4-only fallback. Replay-safe tunables are a **guarantee**, not a configuration. *(Revision 3 said "Optional (reflectively detected, never required)" here, three paragraphs after the header said REQUIRED. That was a stale leftover, not a supersession note, and it is deleted.)*
- `wpimath` — `ArmFeedforward`, `ElevatorFeedforward`, `SimpleMotorFeedforward`, `PIDController`, `ProfiledPIDController`, `TrapezoidProfile`, `ExponentialProfile`, `LinearSystemId`, `LinearQuadraticRegulator`, `Matrix`, `MatBuilder`, `VecBuilder`, `Nat`, `DCMotor`.
- `wpilibj` — `RobotBase`, `Preferences`. **`Timer`, `DriverStation`, `Filesystem` and `Alert` are reached only through `design/06`'s facades** (`Clock`, `MatchContext`, `Platform`, `Alerts`), per ArchUnit rules 2, 3, 10 and 12.
- `wpilibNewCommands` — `SysIdRoutine`, `SysIdRoutineLog`, `Command`, `Subsystem`.
- `ntcore` — `NetworkTableInstance`, `NetworkTableListenerPoller`, `NetworkTableEvent`, `ValueEventData`, `DoublePublisher`, `StringPublisher`, `BooleanEntry`.
- `wpinet` — `edu.wpi.first.net.WebServer` (verified: `start(int port, String path)`, `stop(int port)`).

Vendor libraries (Phoenix 6, REVLib) are reached **only** through `GainSink` implementations that live in `design/01`'s vendor-adapter artifacts (`rootstock-phoenix6`, `rootstock-revlib`). Nothing in `org.rootstock.tuning` or `org.rootstock.control` imports a vendor type.

**Zero third-party math dependencies.** No JGraphT, no Apache Commons, no EJML calls outside what wpimath already exposes.

> **What this costs, restated where a tuning-first adopter will read it.** A team on DogLog or plain Epilogue **cannot adopt Rootstock's tuning system without switching loggers.** There is no `LogBackend` SPI to write — decision 3 deleted it. See [`DESIGN.md`](../DESIGN.md) §11c. This is an exclusion, not a migration path, and it is stated here rather than discovered at install time.

### 2.4 Evidence this is the right scope

From the maintainer's own repositories. Paths are relative to each repository's root, and every
claim below is pinned to a commit or to a read date, because neither repository is part of this
one and neither is reachable from a clone of it:

- `0000-XXXX-Robot-Template` at commit `f02b51d` (2026-07-30): `src/main/java/frc/robot/util/LoggedTunableNumber.java` existed, was well-documented, and **had zero call sites outside its own file.** No IO class exposed `setGains`. Every gain was a `static final` in `Constants.java` behind a `// TUNE` marker. The template author *wanted* live tuning, wrote the primitive, and it never got wired — because wiring it per-mechanism is more work than redeploying once. **A tunable primitive without a mechanism contract that consumes it is dead code.** This is why §3.1 and the `GainSink` requirement are non-negotiable. (Commit `4143604`, 2026-08-07, wired it into ten subsystem files in response to this research. The state described above is what motivated the requirement, not a standing defect in that repository.)
- 8793's 2026 robot code, a separate repository, read 2026-08: `src/main/java/frc/robot/subsystems/ShooterSubsystem.java` held three `InterpolatingDoubleTreeMap`s of ~20 hand-measured points each, populated in a `static {}` block. Every re-measurement was an edit plus `./gradlew deploy`. A grep across `src/main/java` returned **14 `SmartDashboard.put*` calls and zero `getNumber`/`Preferences`** — the dashboard was write-only. This is a real, competition-season team with a genuinely sophisticated shoot-on-the-move solver that still could not change a number at the field.
- Same repository, same read: `src/main/java/frc/robot/constants/Constants.java` defined `TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0;` with conversions written as `angle / 360.0 / GEAR_RATIO` in seven places and a comment admitting "gear ratio is negative, so signs cancel." **Any tuning system that hands a student a number in a unit they cannot reason about is making this worse.** §4 exists because of this line, and **D7**'s positive-only `Reduction` makes it unrepresentable.

---

## 3. Core types

> **Package note (D8).** `TuningTarget` and its supporting value types live in **`org.rootstock.control`**, shipped in the single `rootstock` jar, *not* in `org.rootstock.tuning`. A team with hand-rolled subsystems implements the seam in about thirty lines without adopting either the mechanism layer or the wizard, and the mechanism layer does not have to depend on the tuning package to be tunable. This is the single most important incremental-adoption seam in the library; [`DESIGN.md`](../DESIGN.md) §11b step 4 and `docs/graduation.md` both lead with it.

### 3.1 `TuningTarget` — the SPI the mechanism layer implements

This is the only thing the tuner knows about a mechanism. It is deliberately narrow: raw voltage in, SI state out, plus enough physical description to be safe and to sanity-check the answer.

```java
package org.rootstock.control;

import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.units.SiDomain;

/**
 * The seam between a mechanism and the tuning system.
 *
 * <p>Every quantity is in canonical SI: meters and meters/second for linear mechanisms,
 * radians and radians/second for rotational ones. {@link #siDomain()} declares which.
 * Volts are always volts.
 *
 * <p>Implementations live in the mechanism layer. Rootstock's own {@code Mechanism} implements
 * this; teams with hand-rolled subsystems implement it directly in about thirty lines
 * (DESIGN.md section 11b step 4, and section 14.4 below).
 */
public interface TuningTarget {

  // ---- identity and shape ------------------------------------------------------------

  /** Unique, human-meaningful name. Becomes the NetworkTables namespace. Must not contain '/'. */
  String tuningName();

  /** Which tuning recipe applies. */
  MechanismArchetype archetype();

  /**
   * Linear (meters) or rotational (radians). Derived, never typed by a team:
   * Rootstock's mechanisms return {@code config.units().siDomain()} (D3).
   */
  SiDomain siDomain();

  /**
   * Where the closed loop executes (D5). Tuning asks exactly one question of it —
   * {@link ControlLocation#runsOnMotor()} — which decides the measurement-delay row in
   * section 9.2.1 and whether {@link #getFeedbackVolts()} can be expected to be present.
   *
   * <p>There is no {@code LoopLocation}. [SUPERSEDED-NAME] Revisions 1 through 3 of this document defined a
   * two-value tuning-only enum alongside the builder's four-value one and taught the student
   * both names in the same error message; D5 deletes it.
   */
  ControlLocation controlLocation();

  // ---- open-loop actuation (used by every identification step) -----------------------

  /**
   * Command a raw voltage. Must bypass any closed loop. Must respect device soft limits.
   *
   * <p><b>{@link TuningSupervisor} is the only legal caller.</b> Nothing in
   * {@code org.rootstock.tuning} calls this directly, and {@code TuningSupervisorCallerTest}
   * (section 16.1) fails the build if anything does.
   */
  void setVoltage(double volts);

  /** Immediately neutral the mechanism. Called on every abort path. Must never throw. */
  void stop();

  // ---- measurement ------------------------------------------------------------------

  /** Position in meters or radians. For a COSINE-gravity axis, see {@link #horizontalReferenceSi()}. */
  double measuredSi();

  /** Velocity in m/s or rad/s. */
  double velocitySi();

  /**
   * Acceleration in m/s^2 or rad/s^2. Implementations with no acceleration signal return
   * {@link Double#NaN}; the tuner then differences velocity itself with a
   * {@link org.rootstock.tuning.sysid.CentralDifferenceAccel} filter.
   */
  default double accelerationSi() { return Double.NaN; }

  /** Applied motor voltage as actually measured, if the device reports it. Preferred over the commanded value. */
  default OptionalDouble appliedVolts() { return OptionalDouble.empty(); }

  /** Stator current, for the overcurrent abort. Empty disables that abort with a warning. */
  default OptionalDouble statorCurrentAmps() { return OptionalDouble.empty(); }

  /**
   * The feedback-only contribution of the closed loop this cycle, in volts — i.e. the total
   * closed-loop output minus whatever feedforward the loop applied. Empty when the split is not
   * observable.
   *
   * <p>This is the quantity {@code residualVolts} (section 9.3.1) is built from, and it is what lets
   * the wizard say "raise kS" or "raise kG" instead of "raise kP". When it is empty, the entire
   * steady-state diagnosis branch is disabled and the student is told why — we do not guess.
   *
   * <p>Empty is a legitimate answer, not a bug. A loop with
   * {@code controlLocation().runsOnMotor()} computes its feedforward inside the device, and not
   * every vendor reports the split back.
   *
   * <p><b>Why {@code Optional<Double>} and not {@code OptionalDouble}:</b> this is the exact
   * signature DESIGN.md section 11b compiles in CI, and one boxed double per loop while a
   * refinement step is running is not worth a second spelling of the same seam. It is never
   * called on the disabled path or under FMS.
   */
  default Optional<Double> getFeedbackVolts() { return Optional.empty(); }

  // ---- position reference ------------------------------------------------------------

  /**
   * How this mechanism knows where it is, in the sense the SUPERVISOR cares about:
   * can {@link #measuredSi()} be trusted enough to arm a routine that commands voltage?
   *
   * <p>This is deliberately <b>not</b> {@code org.rootstock.config.FeedbackSpec}, which answers a
   * different question — which sensor is wired where ({@code RotorOnly}, {@code FusedCancoder},
   * {@code RemoteCancoder}, {@code SparkAbsolute}, {@code DioAbsolute}). Two different questions
   * under one name is the bug class this library exists to delete, and {@code config} sits
   * <i>above</i> {@code control} in the dependency graph so the tuning seam could not name it
   * anyway. Rootstock's mechanisms map one to the other in one total function.
   */
  PositionReference positionReference();

  /**
   * True when this mechanism's reported position corresponds to physical reality right now.
   *
   * <p>Every position-based abort in section 7.2 and every gravity-shaped voltage command in
   * section 8.4 is computed against {@link #measuredSi()}. A mechanism that was moved by hand while
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

  /**
   * The absolute sensor's reading in SI, when one exists, for the agreement check in
   * {@code arm()} precondition 10. Empty when there is no absolute source.
   */
  default OptionalDouble absolutePositionSi() { return OptionalDouble.empty(); }

  // ---- physical description ---------------------------------------------------------

  /** Hard travel limits plus the tuning margin, in SI. Required; the supervisor refuses to arm without them. */
  TravelLimits travelLimits();

  /** Physics prior used to sanity-bound the fit and to build the sim plant. */
  PlantPrior plantPrior();

  /**
   * The SI position at which a COSINE-gravity mechanism is HORIZONTAL — i.e. the angle from which
   * the gravity term is {@code kG * cos(measuredSi() - horizontalReferenceSi())}. Zero for a linear
   * axis and for {@link GravityMode#NONE}. Rootstock's mechanisms return
   * {@code units().toSi(config.axis().horizontalReference())}.
   *
   * <p>Revisions 1 through 3 asked a boolean, {@code armZeroIsHorizontal()}. A boolean cannot carry
   * the offset a mechanism actually has, and design/01's {@code RotaryAxis.arm(Angle horizontalAt)}
   * has carried the number since revision 2.
   */
  default double horizontalReferenceSi() { return 0.0; }

  /** Which gravity model applies (D2a). Derived from the axis; a hand-rolled target states it. */
  default GravityMode gravityMode() {
    return archetype().hasGravity()
        ? (archetype() == MechanismArchetype.ARM ? GravityMode.COSINE : GravityMode.CONSTANT)
        : GravityMode.NONE;
  }

  /**
   * "At goal" tolerance in SI — {@code ControlConfig.tolerance} converted through
   * {@code MechanismUnits} (D1b: ControlConfig is the sole owner of tolerance; {@code Gains}
   * has no tolerance field). Used by {@code arm()} precondition 10, by
   * {@link org.rootstock.tuning.diagnostics.StepResponseAnalyzer}, and by the bisection's
   * recentre settle band.
   *
   * <p>{@code NaN} means "I do not have one"; the supervisor then substitutes
   * {@code 0.005 * travelLimits().range()} for a position archetype or
   * {@code 0.01 * plantPrior().freeSpeedSi()} for a velocity one, and narrates the substitution.
   */
  default double toleranceSi() { return Double.NaN; }

  // ---- idle mode (new in revision 4; see section 8.4) --------------------------------

  /**
   * The idle/neutral behavior the device is configured for right now. Empty when the adapter
   * cannot read it back.
   *
   * <p>This exists because of a real failure: the kG drift probe releases the mechanism for half a
   * second and watches which way it falls. On a BRAKE-mode arm or elevator — which is design/01's
   * normal configuration — nothing falls, and revisions 1 through 3 concluded "there is nothing for
   * kG to hold" and set kG = 0. Everything downstream then measured gravity as friction.
   */
  default Optional<NeutralMode> neutralMode() { return Optional.empty(); }

  /**
   * Temporarily override the idle mode for a supervised measurement. Returns false if the adapter
   * cannot do it, in which case section 8.4 takes the breakaway-asymmetry branch instead.
   *
   * <p>The supervisor calls {@link #restoreNeutralMode()} on <b>every</b> exit path, including
   * every abort, <i>before</i> it commands neutral — see section 7.3.
   */
  default boolean overrideNeutralMode(NeutralMode mode) { return false; }

  /** Restore the configured idle mode. Idempotent, never throws. */
  default void restoreNeutralMode() {}

  // ---- gains -------------------------------------------------------------------------

  /** The gains this mechanism is running right now. */
  Gains gains();

  /**
   * Where tuned gains are written. MUST write through to wherever the loop actually runs:
   * on-motor slot configs when {@code controlLocation().runsOnMotor()}, or the wpimath controller
   * objects otherwise. Implementations must be idempotent and must not perform bus traffic when the
   * gains are unchanged.
   *
   * <p>Revisions 1 through 3 spelled this {@code void applyGains(Gains)}. DESIGN.md section 11b
   * compiles {@code GainSink gainSink()}, and a returned sink is what lets the same object also
   * answer {@link GainSink#describeConversion()} for the boot dump and the UI.
   */
  GainSink gainSink();

  // ---- closed loop (used only by the kP/kD steps and the verify step) -----------------

  default boolean supportsClosedLoop() { return false; }

  /** Position setpoint (m or rad) for POSITION archetypes; velocity setpoint (m/s or rad/s) for VELOCITY ones. */
  default void setClosedLoopGoalSi(double goalSi) { /* no-op; supportsClosedLoop() is false by default */ }

  /** True when a motion profile is in use, so the analyzer compares against the profile, not a step. */
  default boolean isProfiled() { return false; }

  /** Measurement delay override; NaN means "use the section 9.2.1 table". */
  default double measurementDelaySeconds() { return Double.NaN; }

  // ---- scheduler integration ---------------------------------------------------------

  /** Subsystem to require, if the team uses command-based. Empty for state-based robots. */
  default Optional<Subsystem> requirement() { return Optional.empty(); }
}
```

Supporting value types, all in `org.rootstock.control`:

```java
package org.rootstock.control;

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

  public boolean isPosition()   { return this == ELEVATOR || this == ARM || this == TURRET || this == STEER; }
  public boolean isVelocity()   { return this == FLYWHEEL || this == DRIVE_VELOCITY; }
  public boolean hasGravity()   { return this == ELEVATOR || this == ARM; }
  public boolean isContinuous() { return this == STEER; }
}

/**
 * Where this mechanism's position reference comes from. The supervisor uses this to decide whether
 * {@link TuningTarget#measuredSi()} can be trusted enough to arm a routine that commands voltage.
 *
 * <p>Named {@code PositionReference}, not {@code FeedbackSpec}: design/01's {@code FeedbackSpec}
 * answers "which sensor is wired where", this one answers "is the number trustworthy right now".
 */
public sealed interface PositionReference {

  /** Only the motor's internal rotor sensor. Zero is wherever the robot booted. */
  record RotorOnly() implements PositionReference {}

  /**
   * A homing routine ran and completed since the last power cycle (limit switch, hard-stop current
   * spike, or a mechanical index). {@code completedThisPowerCycle} is a live supplier, not a flag
   * captured at construction.
   */
  record HomedAgainstSwitch(java.util.function.BooleanSupplier completedThisPowerCycle)
      implements PositionReference {}

  /** A true absolute sensor (CANcoder, through-bore absolute, duty-cycle encoder, potentiometer). */
  record Absolute(String sensorDescription) implements PositionReference {}

  /** Absolute sensor fused with the rotor for resolution (Phoenix 6 FusedCANcoder, etc.). */
  record FusedAbsolute(String sensorDescription) implements PositionReference {}

  /**
   * "Assume we booted at this position." Legal for robot code. NOT a position reference the tuner
   * will accept for a position archetype — see {@link TuningSupervisor#arm()} precondition 9.
   */
  record AssumeAtBoot(double assumedSi) implements PositionReference {}

  default boolean isAbsolute() { return this instanceof Absolute || this instanceof FusedAbsolute; }
}
```

```java
package org.rootstock.control;

import java.util.List;
import org.rootstock.config.ConfigError;

/**
 * All values in SI (m or rad).
 *
 * <p><b>{@code softMargin} is not optional.</b> The tuning supervisor's abort band is derived from
 * these limits and it must be strictly inside them, so that the supervisor always trips before the
 * device's own soft limit clamps silently. A zero margin makes that impossible, and a margin larger
 * than 40% of travel leaves the supervisor no band at all.
 *
 * <p><b>Revision 4 — this record no longer throws.</b> Revisions 1 through 3 rejected an illegal
 * margin from the compact constructor. Every example in this document and in design/01 declares
 * configs as {@code public static final} fields, so that throw surfaces as
 * {@code ExceptionInInitializerError} from {@code frc.robot.RobotConfig.<clinit>}: robot code never
 * starts, the driver station shows red "Robot Code", and the carefully written message becomes a
 * nested cause under JVM class-init frames. DESIGN.md section 14 row 7 declares that failure
 * structurally unrepresentable and design/01 section 5.6 rebuilt its whole validation pipeline to
 * prevent it; this record reintroduced it one package over, in the domain aimed at the least
 * experienced users. Problems are now <b>collected</b> by {@link #validate(String)} and surfaced by
 * {@code TuningRegistry.register(...)} through {@code RootstockRegistry.addAll}, which prints them all
 * at once and enters SAFE_MODE on any FATAL.
 */
public record TravelLimits(double min, double max, double softMargin) {

  /** Smallest legal margin, as a fraction of travel. */
  public static final double MIN_MARGIN_FRACTION = 0.02;
  /** Largest legal margin, as a fraction of travel. Above this the supervisor band collapses. */
  public static final double MAX_MARGIN_FRACTION = 0.40;

  public double range()   { return max - min; }
  public double softMin() { return min + softMargin; }
  public double softMax() { return max - softMargin; }
  public double centre()  { return (min + max) / 2.0; }
  public boolean insideSoft(double x) { return x >= softMin() && x <= softMax(); }

  /**
   * Pure, allocation-free on the happy path, and non-throwing. Returns {@code List.of()} when the
   * limits are legal.
   *
   * @param owner the mechanism name, so the printed error names the thing the student edits
   */
  public List<ConfigError> validate(String owner) {
    if (max > min
        && softMargin >= MIN_MARGIN_FRACTION * range()
        && softMargin <= MAX_MARGIN_FRACTION * range()) {
      return List.of();
    }
    var out = new java.util.ArrayList<ConfigError>(2);
    if (!(max > min)) {
      out.add(new ConfigError(ConfigError.Severity.FATAL, owner, "travelLimits.max",
          String.valueOf(max), "> min (" + min + ")",
          "Travel limits run from min to max. A mechanism with no travel cannot be tuned.",
          ConfigError.callerFrame()));
    } else if (softMargin < MIN_MARGIN_FRACTION * range()) {
      out.add(new ConfigError(ConfigError.Severity.FATAL, owner, "travelLimits.softMargin",
          String.valueOf(softMargin),
          ">= " + (MIN_MARGIN_FRACTION * range()) + " (2% of " + range() + " of travel)",
          "The tuning supervisor's abort band has to fit strictly inside your soft limits, so that "
        + "it stops the mechanism before the device clamps silently. A margin this small leaves it "
        + "no room, and you would get a supervisor that only appears to protect you.",
          ConfigError.callerFrame()));
    } else {
      out.add(new ConfigError(ConfigError.Severity.FATAL, owner, "travelLimits.softMargin",
          String.valueOf(softMargin),
          "<= " + (MAX_MARGIN_FRACTION * range()) + " (40% of " + range() + " of travel)",
          "A margin this large leaves the supervisor no band between the two soft limits. "
        + "Either your travel limits are wrong or your margin is.",
          ConfigError.callerFrame()));
    }
    return out;
  }

  /**
   * A limit set meaning "unbounded", legal only for FLYWHEEL and DRIVE_VELOCITY.
   * Position aborts are disabled for those archetypes; the margin here exists only to satisfy the
   * 2%-of-travel invariant (1e8 is 5% of the 2e9 range).
   */
  public static TravelLimits unbounded() {
    return new TravelLimits(-1e9, 1e9, 1e8);
  }
}
```

```java
package org.rootstock.control;

import edu.wpi.first.math.system.plant.DCMotor;
import java.util.List;
import org.rootstock.config.ConfigError;
import org.rootstock.pure.units.Reduction;

/**
 * Physics prior. Used for three things and three things only:
 * (1) sanity-bounding a fitted gain, (2) building the sim plant, (3) deriving a first-guess
 * kV/kA/kG when the student wants to skip identification, and when {@code Gains.UNTUNED} resolves
 * in simulation (D2c). It is never used in place of measurement.
 *
 * <p><b>D7:</b> the gearbox is a {@link Reduction}, not a raw double. Positive-only, self-describing,
 * and cross-checked at registration against the mechanism's own reduction — because the wizard
 * sanity-bounds every fitted gain against this prior, and a prior that disagrees with the real
 * reduction makes the wizard confidently reject correct fits.
 *
 * <p><b>The motor must be constructed with the real motor count.</b>
 * {@code DCMotor.getKrakenX60Foc(2)} is a two-motor gearbox, not one motor used twice; see section
 * 6.5 for exactly what that does to {@code KtNMPerAmp} and {@code rOhms}, and for the bug it caused.
 */
public record PlantPrior(
    DCMotor motor,                // constructed with the REAL motor count
    Reduction reduction,          // rotor rotations per mechanism (or drum) rotation, always POSITIVE
    double massKg,                // ELEVATOR / DRIVE_VELOCITY: moving mass. NaN if not applicable.
    double moiKgM2,               // ARM / TURRET / FLYWHEEL / STEER: moment of inertia. NaN if not applicable.
    double radiusMetres,          // ELEVATOR effective radius, DRIVE_VELOCITY wheel radius, ARM centre-of-mass length. NaN otherwise.
    double nominalVolts) {        // usually 12.0

  // ---- named constructors, so a team never fills six slots positionally ---------------

  public static PlantPrior elevator(DCMotor motor, Reduction reduction,
                                    double massKg, double effectiveRadiusMetres) {
    return new PlantPrior(motor, reduction, massKg, Double.NaN, effectiveRadiusMetres, 12.0);
  }

  public static PlantPrior arm(DCMotor motor, Reduction reduction,
                               double moiKgM2, double comLengthMetres, double massKg) {
    return new PlantPrior(motor, reduction, massKg, moiKgM2, comLengthMetres, 12.0);
  }

  /**
   * A gravity-free rotating inertia: flywheel, turret, steer axis.
   * <b>Cross-doc note:</b> DESIGN.md section 11b currently writes
   * {@code PlantPrior.flywheel(Reduction.of(1.0), MOI)} with no motor. A prior with no motor curve
   * cannot produce kV, kA or a free-speed ceiling, so the motor argument is required; section 19
   * carries the correction.
   */
  public static PlantPrior flywheel(DCMotor motor, Reduction reduction, double moiKgM2) {
    return new PlantPrior(motor, reduction, Double.NaN, moiKgM2, Double.NaN, 12.0);
  }

  // ---- validation: collected, never thrown (see TravelLimits above) -------------------

  public List<ConfigError> validate(String owner) {
    if (reduction != null && reduction.rotorPerOutput() > 0 && motor != null) return List.of();
    if (motor == null) {
      return List.of(new ConfigError(ConfigError.Severity.FATAL, owner, "plantPrior.motor",
          "null", "a DCMotor built with the real motor count",
          "Every prior in section 6.5 is read off the motor curve. Without it the wizard cannot "
        + "bracket the kG bisection or sanity-bound the fit.", ConfigError.callerFrame()));
    }
    return List.of(new ConfigError(ConfigError.Severity.FATAL, owner, "plantPrior.reduction",
        String.valueOf(reduction == null ? "null" : reduction.rotorPerOutput()), "> 0",
        "A reduction is how many times the MOTOR turns for one turn of the OUTPUT. Encode direction "
      + "with the invert flag on the device, never with a negative ratio. "
      + "(See 8793 Constants.java:45 for why: TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0, with seven "
      + "downstream conversions relying on double sign cancellation.)",
        ConfigError.callerFrame()));
  }

  // ---- derived priors ---------------------------------------------------------------
  //
  // These five are the ONLY things the tuner is allowed to read out of a prior before a
  // measurement exists. They bracket the kG bisection (section 8.4), build the provisional
  // feedback controller that recentres between probes, derive the SysId envelope (section 6.2),
  // and sanity-bound the fit (section 6.5). Every one is a pure function of the record's own
  // components; none touches hardware. All five are pinned by PlantPriorDerivationTest.

  /** kV implied by the motor curve, volts per (m/s) or per (rad/s). See section 6.5. */
  public double kVprior() { /* -A(1,1)/B(1,0) of the LinearSystemId plant */ return 0; }

  /** kA implied by the motor curve, volts per (m/s^2) or per (rad/s^2). See section 6.5. */
  public double kAprior() { /* 1.0/B(1,0) */ return 0; }

  /**
   * Volts required to hold the gravity load at the worst-case pose, as a POSITIVE MAGNITUDE.
   * The sign is never assumed — it is measured in section 8.4 step 0.
   * {@code NaN} for archetypes without gravity.
   */
  public double gravityVoltsPrior() { /* section 6.5 kGprior */ return Double.NaN; }

  /** Free speed at the mechanism, m/s or rad/s. Used for velocity aborts and gentle profiles. */
  public double freeSpeedSi() { /* Kv/G, scaled by radius for a LINEAR domain */ return 0; }

  /** Voltage-limited acceleration at the mechanism, m/s^2 or rad/s^2. */
  public double maxAccelSi() { /* nominalVolts / kAprior() */ return 0; }
}
```

> **Design note — the positive-gearing invariant, and why it is now a `ConfigError` rather than a throw.** `Reduction` (D7) is positive-only at the type level, so `-20 / 200.0` is unrepresentable before validation runs at all — which is strictly better than rejecting it afterwards. What `validate` catches is the residue: a null motor, and a `Reduction` that somehow arrived at zero. A tuner that fits a *negative* kV because the ratio was negative hands a student a physically meaningless number and then a kP that drives the mechanism away from its setpoint. Making it unrepresentable at the type boundary is cheaper than diagnosing it; making the *residual* case a collected error rather than a throw is what keeps the robot booting so the student can read the message.

### 3.2 `Gains` — the canonical gain record

**D1a: `Gains` is exactly seven doubles.** Gravity mode, motion constraints, tolerance, integral windup parameters, neutral mode and manual-control parameters all live on `ControlConfig`, which is where a mechanism's *policy* belongs. A tuner writes gains; it does not write policy.

```java
package org.rootstock.control;

/**
 * Canonical Rootstock gains. ALL gains are expressed in <b>volts per SI unit</b>, matching
 * wpimath exactly:
 *
 * <pre>
 *   kS  volts                       (static friction)
 *   kV  volts / (unit/second)       (velocity)
 *   kA  volts / (unit/second^2)     (acceleration)
 *   kG  volts                       (gravity; multiplied by cos(theta - horizontalRef) for COSINE)
 *   kP  volts / unit                (proportional)
 *   kI  volts / (unit * second)     (integral)
 *   kD  volts / (unit/second)       (derivative)
 * </pre>
 *
 * where "unit" is meters for {@link org.rootstock.units.SiDomain#LINEAR_METERS}
 * and radians for {@link org.rootstock.units.SiDomain#ROTATIONAL_RADIANS}.
 *
 * <p>Conversion to Phoenix 6 output-per-rotation and REVLib duty-cycle-per-rotation happens
 * exactly once, in a {@link GainSink}. Team code never sees vendor units.
 *
 * <p><b>NAMED FIELDS ONLY.</b> There is deliberately no multi-double constructor:
 * {@code reefscape2025/util/custom/GainConstants.java} has a live positional-overload bug where
 * {@code (P,I,D,FF,minOut,maxOut)} silently binds to {@code (P,I,D,S,V,G)}, dropping the
 * feedforward. Builder-only construction makes that class of bug unrepresentable.
 *
 * <p><b>What is NOT here, and where it went (D1a, D1b, D2, D2a):</b> {@code GravityType} is
 * {@link GravityMode} on {@code ControlConfig}, derived from the {@code Axis}, because a team should
 * never type a gravity mode for an elevator. {@code ProfileConstraints} is
 * {@code MotionConstraints} on {@code ControlConfig}, authored in user units and stored in SI.
 * {@code toleranceSi} is {@code ControlConfig.tolerance}. Integral windup is
 * {@code ControlConfig.integral(kI, iZone, iMaxVolts)} — deliberately awkward, and still the only
 * way to enable kI. Keeping {@code Gains} a flat tuple of seven measurable physical quantities is
 * what lets {@code TunedValueStore}, {@code ValueExporter}, the NT schema and the wizard all treat
 * it as one object.
 */
public record Gains(double kP, double kI, double kD,
                    double kS, double kV, double kA, double kG) {

  public static Gains pid(double kP, double kI, double kD) { return new Gains(kP, kI, kD, 0, 0, 0, 0); }

  public Gains withKp(double v) { return new Gains(v, kI, kD, kS, kV, kA, kG); }
  public Gains withKi(double v) { return new Gains(kP, v, kD, kS, kV, kA, kG); }
  public Gains withKd(double v) { return new Gains(kP, kI, v, kS, kV, kA, kG); }
  public Gains withKs(double v) { return new Gains(kP, kI, kD, v, kV, kA, kG); }
  public Gains withKv(double v) { return new Gains(kP, kI, kD, kS, v, kA, kG); }
  public Gains withKa(double v) { return new Gains(kP, kI, kD, kS, kV, v, kG); }
  public Gains withKg(double v) { return new Gains(kP, kI, kD, kS, kV, kA, v); }

  /**
   * Two readable literals instead of seven ternaries. Uses {@code Platform.isReal()}, not
   * {@code RobotBase}, per DESIGN.md section 16 item 3.
   */
  public static Gains realOrSim(Gains real, Gains sim) {
    return org.rootstock.core.compat.Platform.isReal() ? real : sim;
  }

  /**
   * <b>D2c.</b> The placeholder every quickstart, README block and template config uses.
   * {@code kP} is {@link Double#NaN}, which is a real sentinel that tier-3 placeholder detection
   * fires on — unlike the all-zero {@code Gains.zero()} of revisions 1 through 3, which looked like
   * a deliberate choice and was silently a non-functioning mechanism.
   *
   * <p>In <b>simulation</b>, {@code UNTUNED} resolves at mechanism construction to a physics-derived
   * first guess from {@link PlantPrior} (kV/kA from {@code LinearSystemId}, kG from section 6.5's
   * {@code kGprior}, kP from {@link org.rootstock.tuning.FeedbackDesigner}); the mechanism moves,
   * and the boot dump says <i>"these gains were derived from your declared mass, not measured — run
   * the tuning wizard."</i> On <b>real hardware</b> a mechanism constructed with {@code UNTUNED}
   * <b>refuses closed-loop control</b> and holds neutral. Manual control and homing still work.
   *
   * <p>{@code Gains.zero()} is deleted. It was the taught placeholder in revision 3's section 14.1
   * and it is exactly the thing D2c exists to remove.
   */
  public static final Gains UNTUNED = new Gains(Double.NaN, 0, 0, 0, 0, 0, 0);

  public boolean isUntuned() { return Double.isNaN(kP); }

  /**
   * Single-gain setter used by the wizard, keyed by {@link GainId}. Every id published to
   * {@code /Tuning/<Mechanism>/} round-trips through this method and {@link #get(GainId)}; that
   * bijection is pinned by {@code GainsTest} so the NT schema can never drift ahead of the record.
   *
   * <p>There is no case that throws and no case that substitutes. Revision 3's {@code KI} case had
   * to invent an integrator clamp because {@code iMaxVolts} lived on this record and
   * {@code withIntegral} guarded it; D1a moved both to {@code ControlConfig}, so the guard now lives
   * with the thing it guards and this method is a total function.
   */
  public Gains with(GainId id, double value) {
    return switch (id) {
      case KP -> withKp(value);
      case KI -> withKi(value);
      case KD -> withKd(value);
      case KS -> withKs(value);
      case KV -> withKv(value);
      case KA -> withKa(value);
      case KG -> withKg(value);
    };
  }

  public double get(GainId id) {
    return switch (id) {
      case KP -> kP; case KI -> kI; case KD -> kD;
      case KS -> kS; case KV -> kV; case KA -> kA; case KG -> kG;
    };
  }
}
```

```java
package org.rootstock.control;

import org.rootstock.units.SiDomain;

/** Exactly one id per component of {@link Gains}. Seven, and the NT schema is generated from it. */
public enum GainId {
  KS("kS", "V"),
  KV("kV", "V/(unit/s)"),
  KA("kA", "V/(unit/s^2)"),
  KG("kG", "V"),
  KP("kP", "V/unit"),
  KI("kI", "V/(unit*s)"),
  KD("kD", "V/(unit/s)");

  private final String key; private final String unit;
  GainId(String key, String unit) { this.key = key; this.unit = unit; }
  public String key() { return key; }
  public String unitTemplate() { return unit; }
  public String unitFor(SiDomain d) {
    return unit.replace("unit", d == SiDomain.LINEAR_METERS ? "m" : "rad");
  }
}
```

**Where the integral guard went, so it is not lost.** `ControlConfig` (design/01 §5.3) owns it:

```java
// org.rootstock.config.ControlConfig.Builder -- design/01 owns this; reproduced for the contract.
//
// The ONLY way to enable integral gain. There is deliberately no withI(double), and the three
// arguments must be supplied together, because integral windup with no clamp is how arms slam.
public Builder integral(double kI, double iZone, double iMaxVolts);
```

Its validation is a **collected `ConfigError`**, not a throw — `kI != 0 && !(iMaxVolts > 0)` is FATAL with the message *"Non-zero kI requires iMaxVolts > 0. Integral windup with no clamp is how arms slam. If you are reaching for kI to fix steady-state error, you almost certainly need kS or kG instead."* The wizard's `REVIEW` panel writes `iZone` and `iMaxVolts` as ordinary tunables (§5.5), so the failure revision 2 row 10 fixed — a student enabling kI from the panel and taking the wizard down mid-session — cannot happen: there is no constructor to trip.

**Cross-doc contract:** `ConfigRegistry` must reject a live `/Tuning/<M>/kI` edit that would leave `iMaxVolts == 0`, publishing the same sentence to `/RootstockTuner/result/warnings` rather than applying it. `GainsTest` and `ControlConfigIntegralTest` pin both halves.

### 3.3 Building the wpimath objects from `Gains`

Team code never does this by hand; `Controllers` does it and is the only place `calculateWithVelocities` is called.

**`Controllers` takes explicit primitives, not a `ControlConfig`.** `org.rootstock.config` sits *above* `org.rootstock.control` in the dependency graph (`ControlConfig` holds a `Gains`), so control cannot name it. The mechanism layer converts its `ControlConfig` fields to SI through `MechanismUnits` and passes them in.

```java
package org.rootstock.control;

import edu.wpi.first.math.controller.*;
import edu.wpi.first.math.trajectory.ExponentialProfile;
import edu.wpi.first.math.trajectory.TrapezoidProfile;

public final class Controllers {
  private Controllers() {}

  /** Live-mutable feedforward. Retuning calls setKs/setKv/setKa/setKg — never reallocates. */
  public sealed interface Feedforward permits SimpleFf, ElevatorFf, ArmFf {
    /**
     * Discrete plant-inversion feedforward for the CURRENT loop iteration.
     *
     * @param positionRad arm angle in radians FROM HORIZONTAL (ignored by SimpleFf and ElevatorFf)
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
    @Override public double calculate(double posRadFromHorizontal, double cur, double next) {
      return ff.calculateWithVelocities(posRadFromHorizontal, cur, next);
    }
    @Override public void update(Gains g) { ff.setKs(g.kS()); ff.setKg(g.kG()); ff.setKv(g.kV()); ff.setKa(g.kA()); }
  }

  /**
   * Dispatch on {@link GravityMode}, not on the archetype: the gravity model is a property of the
   * mechanism's geometry (D2a), and design/01's {@code Axis} already derived it.
   */
  public static Feedforward feedforward(GravityMode gravity, Gains g, double dtSeconds) {
    return switch (gravity) {
      case COSINE   -> new ArmFf(g, dtSeconds);
      case CONSTANT -> new ElevatorFf(g, dtSeconds);
      case NONE     -> new SimpleFf(g, dtSeconds);
    };
  }

  /**
   * Build a motion profile straight from measured kV/kA. This is the single reason a student
   * never has to guess a max velocity again.
   */
  public static ExponentialProfile exponentialProfile(Gains g, double maxInputVolts) {
    return new ExponentialProfile(
        ExponentialProfile.Constraints.fromCharacteristics(maxInputVolts, g.kV(), g.kA()));
  }

  /** Constraints arrive already converted to SI by {@code MechanismUnits}; see D2. */
  public static TrapezoidProfile trapezoidProfile(double maxVelocitySi, double maxAccelerationSi) {
    return new TrapezoidProfile(new TrapezoidProfile.Constraints(maxVelocitySi, maxAccelerationSi));
  }

  /**
   * Feedback controller. Continuous input is enabled automatically for a continuous axis.
   * {@code toleranceSi}, {@code iZone} and {@code iMaxVolts} come from {@code ControlConfig} (D1a/D1b)
   * — they are not on {@link Gains}.
   */
  public static PIDController pid(Gains g, boolean continuous, double toleranceSi,
                                  double iZone, double dtSeconds) {
    var c = new PIDController(g.kP(), g.kI(), g.kD(), dtSeconds);
    c.setTolerance(toleranceSi);
    if (g.kI() != 0.0) c.setIZone(iZone);
    if (continuous) c.enableContinuousInput(-Math.PI, Math.PI);
    return c;
  }
}
```

> **Verified:** `ArmFeedforward.calculateWithVelocities(double currentAngle, double currentVelocity, double nextVelocity)`, `ElevatorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)`, `SimpleMotorFeedforward.calculateWithVelocities(double currentVelocity, double nextVelocity)` all exist in WPILib 2026, and both `calculate(pos, vel, accel)` and `calculate(angle, curVel, nextVel, dt)` are `@Deprecated(forRemoval = true, since = "2025")`. All three classes expose `setKs/setKv/setKa` (`setKg` on Arm/Elevator), so live retuning never reallocates. `ExponentialProfile.Constraints.fromCharacteristics(double maxInput, double kV, double kA)` is verified. `PIDController.setIZone(double)` and `enableContinuousInput(double, double)` are verified.

---

## 4. Canonical units and the vendor boundary

### 4.1 The problem this solves

The same physical mechanism, the same physical behavior, three different numbers:

| Where the loop runs | kP unit | Same steer motor's real starting kP |
|---|---|---|
| wpimath on the RIO (`ControlLocation.RIO_FULL`) | volts per radian | ~7 (derived) |
| Phoenix 6 `Slot0Configs` (VoltageOut) | output per *rotation* | 100 (CTRE Tuner X generated) |
| REVLib `ClosedLoopConfig` | duty cycle per rotation | 0.01 (YAGSL SparkMax default) |

That is a 10,000× spread for one mechanism. `0000-XXXX-Robot-Template`'s `src/main/java/frc/robot/subsystems/swerve/ModuleIOTalonFX.java:82`, at commit `f02b51d`, already fights this by hand (`config.Slot0.kV = SwerveConstants.DRIVE_kV * 2.0 * Math.PI;`) with a unit test pinning the invariant. Good instinct, wrong layer.

**Decision:** Rootstock gains are *always* volts-per-SI. Conversion happens exactly once, in a `GainSink`. Every number the tuner shows, stores, or writes back to source is in these units. A number a student learns from the WPILib arm tutorial transfers unchanged to their Kraken or their NEO.

### 4.2 `GainSink`

```java
package org.rootstock.control;

/**
 * Converts canonical volts-per-SI gains into whatever the actual control loop wants,
 * and applies them. Implemented once per vendor, in design/01's vendor-adapter artifacts.
 *
 * <p>Implementations MUST:
 * <ul>
 *   <li>be idempotent — {@code apply(g)} twice with the same {@code g} performs no bus traffic;</li>
 *   <li>never allocate on the no-change path;</li>
 *   <li>use the non-blocking {@code applyFast} path (zero-timeout, no read-back) — never the
 *       blocking {@code applyVerified} — because this runs while a slider is being dragged;</li>
 *   <li>return false and raise an {@code Alerts.warning(group, text, MatchImpact.PIT_ONLY)} rather
 *       than throwing when the device rejects a config.</li>
 * </ul>
 *
 * <p>A sink is constructed knowing three things about its mechanism that {@link Gains} deliberately
 * does not carry (D1a/D2a): the {@link GravityMode}, the SI-units-per-mechanism-rotation factor,
 * and the horizontal reference for COSINE gravity.
 */
public interface GainSink {

  /** @return true if the gains were accepted (or unchanged); false if the device rejected them. */
  boolean apply(Gains gains);

  /** Human-readable description of the conversion, dumped to the log at boot and shown in the UI. */
  default String describeConversion() { return ""; }

  /**
   * Meters-per-mechanism-rotation (LINEAR_METERS) or radians-per-mechanism-rotation
   * (ROTATIONAL_RADIANS = 2*pi). This is the ONLY number a Phoenix/REV sink needs to convert every
   * gain, and it is exactly {@code MechanismUnits.siPerOutputRotation()}.
   */
  default double siUnitsPerMechanismRotation() { return 2.0 * Math.PI; }
}
```

Conversion rules, stated once so the vendor adapters have no room to guess. Let `U = siUnitsPerMechanismRotation()` (meters per drum-or-wheel rotation for `LINEAR_METERS`, `2*PI` for `ROTATIONAL_RADIANS`), and assume the device's feedback is configured so **one device "rotation" equals one mechanism rotation** (Phoenix `FeedbackConfigs.SensorToMechanismRatio`, REV `ClosedLoopConfig` with a position conversion factor):

| Canonical | Phoenix 6 `Slot0Configs` with a *Voltage* request | REVLib `ClosedLoopConfig`, voltage-compensated at `Vnom` |
|---|---|---|
| `kP` [V/SI] | `kP * U` (V per mechanism rotation) | `kP * U / Vnom` (duty cycle per rotation) |
| `kI` [V/(SI·s)] | `kI * U` | `kI * U / Vnom` |
| `kD` [V/(SI/s)] | `kD * U` (V per rps) | `kD * U / Vnom` **[UNVERIFIED]** — REV's derivative time base is per-second while Phoenix's is per-rps; the scalar conversion is exact for Phoenix and approximate for REV. Report this in `describeConversion()`. |
| `kS` [V] | `kS` (unchanged) | `kS / Vnom` |
| `kV` [V/(SI/s)] | `kV * U` (V per rps) | `kV * U / (60 * Vnom)` — REV velocity is **RPM** |
| `kA` [V/(SI/s²)] | `kA * U` | `kA * U / (60 * Vnom)` |
| `kG` [V], `GravityMode.CONSTANT` | `kG`, `GravityTypeValue.Elevator_Static` | `kG / Vnom` into REV's `FeedForwardConfig.kG` |
| `kG` [V], `GravityMode.COSINE` | `kG`, `GravityTypeValue.Arm_Cosine`, plus a **negated** `GravityArmPositionOffset` from the horizontal reference | `kG / Vnom` into REV's `kCos` — and see the Tier-1 validation rule below |

Three hard requirements on the sinks:

1. **`GravityMode.COSINE` on Phoenix.** Phoenix's cosine reference is the *device's* position zero. The Phoenix adapter configures `Slot0Configs.GravityArmPositionOffset` (negated — `design/01` §3.5.4) from `TuningTarget.horizontalReferenceSi()`, and Phoenix clamps that field to ±0.25 rot, which `design/01`'s `GravityOffsetRangeTest` pins. The wizard has a dedicated check for the whole chain (§8.4, the three-angle kG test).
2. **`GravityMode.COSINE` on REVLib.** **Verified:** REVLib has `kCos`, which relates encoder position to *absolute mechanism position* with no offset field. There is therefore a Tier-1 `Validation` rule (`DESIGN.md` §5.6): for a SPARK with `COSINE` gravity and a non-zero horizontal reference, emit a FATAL `ConfigError` reading *"REVLib has no arm-position offset for kCos. Re-zero the SPARK absolute encoder so it reads 0 with the arm horizontal, or move this mechanism to `ControlLocation.RIO_FULL`."* This is the one place D2a's single `GravityMode` genuinely does not map across both vendors, and the design says so rather than claiming it does.
3. **`StaticFeedforwardSignValue.UseClosedLoopSign`** should be set for position loops so kS is applied in the direction of *error*, not of measured velocity — otherwise kS dithers when the mechanism is stopped at its setpoint.

#### The worked conversion, on the one elevator this document uses everywhere

> **Revision 4 — the π-factor correction.** Revisions 1 through 3 printed *"1 mechanism rotation = 0.0879 m of travel (drum 5.5 in circumference × 2 stages)"* in the headline example for the "exact conversion a CSA can read" claim, in the document whose entire reason to exist is unit correctness. **5.5 in × 2 stages is 11.0 in, which is 0.2794 m — the printed figure was `0.2794 / π`.** Every downstream number in the demo, and §5.5's meta JSON, derived from it. Below is the corrected derivation, printed in full, on the same plant `design/01` §5.4 uses so that the two documents' numbers are the same numbers.

**The plant.** Two Kraken X60s with FOC, a 12:1 gearbox, a 22-tooth #25-chain sprocket, 2-stage cascade rigging, 55 in of travel, 24 lb of moving mass — i.e. `design/01`'s `RobotConfig.ELEVATOR`, verbatim.

```
Geometry, derived:
  chain advance per drum rotation   22 teeth x 0.250 in pitch  = 5.500 in
  cascade rigging                   x 2 stages                 = 11.000 in
  U = SI units per mechanism rot    11.000 in x 0.0254 m/in    = 0.279400 m
  effective radius (for the priors) U / (2*pi) = 0.2794 / 6.283185 = 0.0444679 m
  travel                            55.000 in                  = 1.397000 m
```

> **A 0.32% cross-document discrepancy, named rather than hidden.** `design/01` §4.3's `LinearAxis.sprocket` computes travel from the *pitch-circle circumference* — pitch diameter = pitch / sin(π/teeth) = 0.250 / sin(π/22) = 1.75669 in, radius 0.878345 in, `2πr × stages` = **0.280293 m** — and asserts that figure to six significant figures in `DescribeSnapshotTest`. For a **chain over a sprocket** the correct advance is exactly `teeth × pitch` (the links sit on the polygon, not the circle), which is **0.279400 m**; the pitch-circle figure is 0.32% high. This document uses **0.2794 m**. **Cross-doc action (§19):** `design/01` §4.3 should compute `LinearAxis.sprocket` as `teeth × pitch × stages` and keep `2πr` for `LinearAxis.pulley` and for true drums, and `DescribeSnapshotTest`'s expected block should be regenerated. Until it is, the two documents differ by 0.32% on this one mechanism, and that is disclosed here rather than papered over.

`describeConversion()` output, dumped to the log at boot and rendered in the UI — **every number below is computed from `U = 0.2794` and the final gains of §11.5c:**

```
Elevator gain conversion (Phoenix 6, VoltageOut, ControlLocation.ON_MOTOR_PROFILED)
  1 mechanism rotation = 0.2794 m of travel
      (22-tooth #25 sprocket: 22 x 0.250 in = 5.500 in of chain, x 2 cascade stages = 11.000 in)
  kP 128.000 V/m      -> Slot0.kP 35.7632   (V per mechanism rotation)   = 128.000 x 0.2794
  kV   5.000 V/(m/s)  -> Slot0.kV  1.3970   (V per rps)                  =   5.000 x 0.2794
  kA   0.060 V/(m/s^2)-> Slot0.kA  0.016764                              =   0.060 x 0.2794
  kD   4.930 V/(m/s)  -> Slot0.kD  1.3774                                =   4.930 x 0.2794
  kS   0.220 V        -> Slot0.kS  0.2200   (unchanged -- volts are volts)
  kG   0.2528 V       -> Slot0.kG  0.2528, GravityType = Elevator_Static (GravityMode.CONSTANT)
```

The same gains through the REVLib sink at `Vnom = 12.0 V`, for the same mechanism on a NEO:

```
  kP 128.000 V/m       -> ClosedLoop.p  2.980267    = 128.000 x 0.2794 / 12
  kD   4.930 V/(m/s)   -> ClosedLoop.d  0.114787    =   4.930 x 0.2794 / 12   [UNVERIFIED time base]
  kS   0.220 V         -> FeedForward.s 0.018333    =   0.220 / 12
  kV   5.000 V/(m/s)   -> FeedForward.v 0.00194028  =   5.000 x 0.2794 / (60 x 12)
  kA   0.060 V/(m/s^2) -> FeedForward.a 0.0000232833=   0.060 x 0.2794 / (60 x 12)
  kG   0.2528 V        -> FeedForward.g 0.021067    =   0.2528 / 12
```

That block alone answers guineawheek's "which knob is misconfigured?" question for the whole class of unit bugs — and `GainConversionTest` (§16.1) round-trips every row to 1e-9 in both directions, so the block cannot drift from the code that produces it.

---

## 5. Tunable values infrastructure

### 5.1 Requirements, and where each comes from

| Requirement | Source |
|---|---|
| Publish plain NT4 doubles under `/Tuning/<Mechanism>/<gain>` | AdvantageScope tuning mode reads the `/Tuning` table; Elastic Text Display and Number Slider are editable. This is the only path that works in **both** with zero setup. |
| ~~No AdvantageKit dependency~~ — **WITHDRAWN by maintainer decision 3.** Rootstock now requires AdvantageKit, exactly as 6328's `LoggedTunableNumber` and `TunableControls` do. This is no longer a point of differentiation and must not be claimed as one. | The remaining requirements in this table — one poller, plain NT4 doubles readable by both dashboards, FMS default-deny in constant time, the `/applied` echo, 4-tier persistence — stand on their own merits and never depended on this row. |
| Never touch SmartDashboard or Shuffleboard | Both deleted in WPILib 2027. YAMS's `NT:/SmartDashboard/.../Live Tuning` path dies in January. |
| Default-deny under FMS, with a constant-time disabled path | DogLog gates by default; 6328's does not. FrcCatalyst returns cached defaults in constant time. Rootstock consults `FmsPolicy.tunablesLocked()` (design/06), which is DogLog's semantic copied verbatim. |
| Mechanism name required at construction | `TunableControls`' stated motivation is collisions between controller instances sharing a name. |
| Change-gated vendor writes | FrcCatalyst calls `checkAndApply(motor)` every periodic; unguarded that is a CAN flood. |
| Unit + range metadata | DogLog carries units so AdvantageScope renders them. 6328's does not. |
| One JNI call per loop regardless of tunable count | **D11a(b).** Revision 1's model was ~250 `DoubleSubscriber.get()` calls per loop on the `DESIGN.md` §10A robot. |
| A path from a good dashboard value back into source | **No FRC library we surveyed closes this loop** (see §15). Stated as a survey result, not as an absolute. |

### 5.2 `TuningRegistry` — the single entry point

```java
package org.rootstock.tuning;

import java.util.List;
import org.rootstock.control.TuningTarget;
import org.rootstock.config.ConfigError;

/**
 * Process-wide owner of every tunable value and every {@link TuningTarget}.
 *
 * <p>Tuning mode is OFF by default under FMS and ON otherwise. When off, every
 * {@code get()} returns a cached primitive with zero NetworkTables traffic and zero allocation.
 *
 * <p><b>Two lifecycle hooks, not one, and neither is a free-standing periodic() the team calls</b>
 * (DESIGN.md section 16 item 3, and the section 6 runtime diagram):
 * <ul>
 *   <li><b>{@code LifecycleHook} priority 30, every loop</b> — {@link #drainPoller()} performs the
 *       single {@code NetworkTableListenerPoller.readQueue()} and dispatches by topic name. This
 *       must run every loop or the queue grows without bound.</li>
 *   <li><b>One {@code SliceScheduler} slice named {@code "Tuning"}</b> — {@link #slice()} does the
 *       rate-gated work: the 10 Hz {@code GainSink} write-through, the {@code /applied} echoes, and
 *       the metadata republish. Revisions 1 through 3 owned a private rate gate for this, which is
 *       exactly what the slice scheduler exists to replace.</li>
 * </ul>
 */
public final class TuningRegistry {

  private static final String TABLE = "Tuning";   // -> NT topic prefix "/Tuning"

  private TuningRegistry() {}

  // ---- global mode -------------------------------------------------------------------

  /**
   * Enable or disable live tuning. Call once from robot construction.
   * The default is {@code !MatchContext.isFMSAttached()}, re-evaluated at every
   * disabled-to-enabled edge so a mid-session FMS connection disables tuning.
   *
   * <p>{@code MatchContext}, never {@code DriverStation}: it is the only class in the library
   * permitted to read the driver station (ArchUnit rule 10), and it latches.
   */
  public static void setTuningEnabled(boolean enabled) { /* ... */ }

  public static boolean isTuningEnabled() { /* ... */ return false; }

  /**
   * Opt in to live tuning while connected to an FMS. Raises
   * {@code Alerts.warning("Tuning", ..., MatchImpact.BLOCKS_MATCH)} for as long as it is active.
   * There is no way to do this silently.
   */
  public static void allowUnderFms() { /* ... */ }

  // ---- tunable creation --------------------------------------------------------------

  /** A standalone tunable double, e.g. {@code TuningRegistry.tunable("Vision", "maxTagDistance", 6.0, "m")}. */
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit) { /* ... */ return null; }

  /** With a slider range, which the layout generator uses to emit a Number Slider instead of a Text Display. */
  public static TunableDouble tunable(String namespace, String key, double defaultValue, String unit,
                                      double min, double max) { /* ... */ return null; }

  /**
   * A standalone tunable <b>boolean</b> — a pit switch, not a number.
   * Published as a plain boolean topic at {@code /Tuning/<namespace>/<key>}, drained by the
   * same single poller as every double (D11a), and off under FMS by the same default-deny
   * rule as every other tunable (D11).
   *
   * <p>The motivating caller is {@code design/03} §7.2's per-camera kill switch:
   * {@code VisionFilters.enabledWhen(TuningRegistry.tunableFlag("Vision", "camera0Enabled", true))}
   * lets a team disable a dead camera in the pit without a redeploy. Contract <b>C8</b> of
   * {@code design/03} §2.7 is this method, and it names it {@code tunableFlag}.
   *
   * <p><b>Why a distinct name rather than a {@code tunable(...)} overload.</b> A boolean has no
   * unit, so it cannot take the {@code String unit} argument every {@code tunable(...)} overload
   * requires, and an overload set distinguished only by the type of the third argument makes the
   * reader count arguments to learn what they get back. The returned type differs
   * ({@link TunableBoolean}, not {@link TunableDouble}) and so does the wire type, so the name
   * differs too — one concept, one name, which is the rule this library exists to enforce.
   */
  public static TunableBoolean tunableFlag(String namespace, String key, boolean defaultValue) { /* ... */ return null; }

  /** The seven-gain set for one mechanism. This is what mechanisms use. */
  public static TunableGains gains(TuningTarget target) { /* ... */ return null; }

  // ---- target registration -----------------------------------------------------------

  /**
   * Register a mechanism with the wizard.
   *
   * <p>Returns the <b>collected</b> configuration errors rather than throwing any of them —
   * {@code TravelLimits.validate}, {@code PlantPrior.validate}, a duplicate name, and the D7
   * cross-check below all land here. {@code RootstockRegistry.addAll} (D27) is the caller, and it
   * prints every error in the robot at once and enters SAFE_MODE if any is FATAL.
   *
   * <p><b>D7 cross-check, run here and nowhere else:</b>
   * {@code PlantPrior.reduction().rotorPerOutput()} must equal the mechanism's own
   * {@code Reduction.rotorPerOutput()} within 1%. If they disagree the registration is refused with
   * a FATAL {@code ConfigError} naming both numbers — because the wizard sanity-bounds every fitted
   * gain against the prior, and a prior built on a different gearbox makes the wizard confidently
   * reject correct fits.
   */
  public static List<ConfigError> register(TuningTarget target) { /* ... */ return List.of(); }

  public static List<TuningTarget> targets() { /* ... */ return List.of(); }

  // ---- lifecycle ---------------------------------------------------------------------

  /**
   * {@code LifecycleHook} priority 30, called from {@code RootstockLifecycle.beforeUserPeriodic()},
   * BEFORE subsystem periodic. Performs exactly one {@code readQueue()} and dispatches the events.
   * Costs one boolean check and one early return when tuning is disabled.
   */
  static void drainPoller() { /* ... */ }

  /**
   * The {@code SliceScheduler} slice. Rate-gated work only: {@code TunableGains.checkAndApply()},
   * the {@code /applied} echoes, and the per-mechanism metadata republish.
   */
  static void slice() { /* ... */ }
}
```

### 5.3 `TunableDouble`

```java
package org.rootstock.tuning;

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
 *   <li>{@link #onChange(DoubleConsumer)} - the DogLog idiom, and the one Rootstock uses
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

  /** Register a callback fired from the priority-30 hook whenever the value changes. */
  public TunableDouble onChange(DoubleConsumer action) { /* ... */ return this; }

  /** Fires once when ANY of {@code others} changes, handing back all values in declaration order. */
  public static void ifChanged(int id, java.util.function.Consumer<double[]> action, TunableDouble... others) { /* ... */ }

  /** Overwrite the live value from code (the wizard does this when a step is accepted). */
  public void set(double value) { /* ... */ }

  public String fullKey() { /* ... */ return ""; }   // e.g. "/Tuning/Elevator/kP"
  public String unit() { /* ... */ return ""; }
}
```

**`TunableBoolean` — the same object with a boolean topic** (`design/03` §2.7 contract **C8**). It is deliberately the smaller surface: there is no `unit()`, no slider range, and no `ifChanged` fan-in, because the callers are pit switches rather than swept quantities.

```java
package org.rootstock.tuning;

import edu.wpi.first.util.function.BooleanConsumer;   // WPILib's; the JDK has no BooleanConsumer
import java.util.function.BooleanSupplier;

/**
 * An NT4-backed boolean with a compile-time default, created by
 * {@code TuningRegistry.tunableFlag(namespace, key, defaultValue)}.
 *
 * <p>{@code implements BooleanSupplier} is the load-bearing part of the contract, not a
 * convenience: it is what lets a handle be passed straight into
 * {@code VisionFilters.enabledWhen(BooleanSupplier)} (design/03 §7.2) and into
 * {@code Trigger}/{@code Commands} without the vision or command domain importing anything
 * from {@code org.rootstock.tuning}.
 *
 * <p>Same lifecycle as {@link TunableDouble} in every respect that matters: one shared
 * {@code NetworkTableListenerPoller}, one {@code readQueue()} per loop (D11a), one boolean
 * publisher created once at construction with {@code setDefault(defaultValue)}, and a cached
 * primitive field on the hot path. When tuning is disabled — which is the default under FMS
 * (D11) — {@link #get()} returns the compile-time default forever with zero NT traffic, so a
 * kill switch left flipped in the pit <b>cannot</b> follow the robot onto the field.
 */
public final class TunableBoolean implements BooleanSupplier {

  /** Current value. When tuning is disabled this returns the cached default with no NT read. */
  public boolean get() { /* ... */ return false; }

  @Override public boolean getAsBoolean() { return get(); }

  /** The compile-time default, regardless of tuning mode. */
  public boolean defaultValue() { /* ... */ return false; }

  /** Per-caller change detection, identical in contract to {@link TunableDouble#hasChanged(int)}. */
  public boolean hasChanged(int id) { /* ... */ return false; }

  /** Register a callback fired from the priority-30 hook whenever the value changes. */
  public TunableBoolean onChange(BooleanConsumer action) { /* ... */ return this; }

  /** Overwrite the live value from code. */
  public void set(boolean value) { /* ... */ }

  public String fullKey() { /* ... */ return ""; }   // e.g. "/Tuning/Vision/camera0Enabled"
}
```

*(API check, 2026-08-08: `BooleanPublisher` declares `void setDefault(boolean value)`, `default void set(boolean value)` and `void set(boolean value, long time)`; `NetworkTableValue` declares `boolean getBoolean()` and `boolean isBoolean()` alongside `getDouble()`/`isDouble()`; `LogTable` declares `void put(String, boolean[])` and `boolean[] get(String, boolean[])`; and `edu.wpi.first.util.function.BooleanConsumer` — **not** `java.util.function`, which has no `BooleanConsumer` — declares `void accept(boolean value)`.)*

**Implementation contract — one poller, one queue drain, one logged input (D11a).**

> **Revision 4 — this is the part revision 3 got wrong twice.** Revision 3's contract said *"`TuningRegistry.periodic()` performs exactly one `entry.get()` per tunable per loop"* and §5.8 budgeted **144** JNI calls per loop for twelve mechanisms. `DESIGN.md` **D11a(b)** replaced that model with one `NetworkTableListenerPoller` subscribed to the `/Tuning` prefix, drained by a single `readQueue()` — *"one JNI call per loop regardless of tunable count, and no lost intermediate values"* — and the master's package tree names *"`TuningRegistry` (ONE `NetworkTableListenerPoller`)"*. The contract below is that model.

- **One poller for the whole table.** `TuningRegistry` holds a single
  `NetworkTableListenerPoller` obtained from `new NetworkTableListenerPoller(NetworkTableInstance.getDefault())`,
  with **one** listener registered:
  `poller.addListener(new String[] {"/Tuning/"}, EnumSet.of(NetworkTableEvent.Kind.kValueAll))`.
  *(Verified 2026-08-08: `NetworkTableListenerPoller(NetworkTableInstance)`, `int addListener(String[] prefixes, EnumSet<NetworkTableEvent.Kind>)`, `NetworkTableEvent[] readQueue()`, `void close()`. `NetworkTableEvent.Kind.kValueAll` is documented as "Topic value updated (network or local)".)*
- **One drain per loop.** `TuningRegistry.drainPoller()` calls `poller.readQueue()` exactly once and walks the returned array. For each event, `e.valueData.getTopic().getName()` is the dispatch key and `e.valueData.value.getDouble()` is the value. *(Verified: `ValueEventData` exposes `int topic`, `int subentry`, `NetworkTableValue value`, and `Topic getTopic()`.)* Handles live in a `LinkedHashMap<String, TunableDouble>` keyed by full NT key, iterated in insertion order — never a `HashMap`, per replay rule 2.
  - **`TunableBoolean` shares this drain; it does not get a second one.** Flags created by `tunableFlag(...)` (§5.2, `design/03` contract C8) live in a second `LinkedHashMap<String, TunableBoolean>` with the same key space and the same insertion-order iteration. The dispatch is `if (e.valueData.value.isBoolean())` → boolean map, `else if (isDouble())` → double map, and an event matching neither map is ignored rather than logged, because `/Tuning/` is a public NT prefix a dashboard can write anything into. *(Verified 2026-08-08: `NetworkTableValue` declares `boolean isBoolean()`, `boolean getBoolean()`, `boolean isDouble()`, `double getDouble()`.)* **The invariant D11a(b) actually pins is one `readQueue()` per loop regardless of tunable count — two `HashMap` lookups per *event* do not touch it**, and there is still exactly one JNI call and one `processInputs`.
- **Each `TunableDouble` caches a `double` field.** `get()` reads the field. There is **no NT access on the hot path**, and no per-tunable subscriber object.
- **Publication is one-shot at construction:** one `DoublePublisher` — or one `BooleanPublisher` for a `TunableBoolean` — with `setDefault(defaultValue)` called once. There is no periodic re-publish, so a stopped robot's tunables do not churn the NT server. *(Verified 2026-08-08: `BooleanPublisher` declares `void setDefault(boolean value)`.)*
- **When tuning is disabled**, `drainPoller()` returns after one `if`, the poller is never read, and every cached field holds the compile-time default forever. Zero NT reads, zero allocation, zero CAN traffic. This matches FrcCatalyst's constant-time disabled path.
- **Change detection compares with `!=` on the raw double**, exactly as 6328 does. No epsilon: a dashboard edit is always an exact new value, and an epsilon would silently swallow a deliberate 4-decimal kG adjustment, which is precisely the change WPILib's arm tutorial says you must be able to make.
- **The honest latency number is 100–150 ms, not "the next loop"** (D11a(a)). AdvantageScope and Elastic are NT *clients*; the robot is the server; a client publisher's default update period is 100 ms unless it sets `PubSubOption.periodic` or explicitly flushes. Intermediate slider values are coalesced away by the *client*, not by us. Every document says this.

### 5.4 `TunableGains` — write-through, change-gated, rate-limited

```java
package org.rootstock.tuning;

import org.rootstock.control.Gains;

/**
 * The seven-gain set for one mechanism, published as editable doubles under
 * {@code /Tuning/<Mechanism>/}. When any gain changes, the new {@link Gains} is pushed
 * through the mechanism's {@code TuningTarget.gainSink().apply(...)}, so a slider move actually
 * reaches the Slot0Configs instead of sitting in an NT table next to them.
 */
public final class TunableGains {

  /** Current gains. Cheap; returns a cached immutable record. */
  public Gains get() { /* ... */ return Gains.UNTUNED; }

  /**
   * Poll the cached fields, and if anything changed, call {@code gainSink().apply(...)} and publish
   * the {@code /applied} echo. Called for you by the {@code "Tuning"} slice; you never write
   * {@code checkAndApply} yourself.
   *
   * <p>Rate limiting: at most one apply per {@link #MIN_APPLY_PERIOD_SEC} seconds per mechanism,
   * and only when at least one gain actually differs. Dragging a slider therefore produces
   * about 10 config writes per second, not 50 — and each is the non-blocking {@code applyFast}
   * (zero timeout, no read-back), never the blocking {@code applyVerified}.
   */
  void checkAndApply() { /* package-private; called by TuningRegistry.slice() */ }

  public static final double MIN_APPLY_PERIOD_SEC = 0.100;

  /** Overwrite from code (the wizard does this on Accept). Publishes to NT and applies immediately. */
  public void set(Gains gains) { /* ... */ }

  /** Revert every gain to its compile-time default and apply. Bound to the wizard's global Abort. */
  public void resetToDefaults() { /* ... */ }

  /** The exact conversion that will be performed, for display. Delegates to the sink. */
  public String describeConversion() { /* ... */ return ""; }
}
```

**The `/applied` echo (D11a(c)).** After a successful apply, `TunableGains` publishes the value the sink actually accepted to `/Tuning/<Mechanism>/<gain>/applied`. The round trip is therefore visible on the dashboard, and a rejected config is diagnosable without a log pull: the slider says 128.0 and the echo says 80.0, and the student can see it.

### 5.5 NetworkTables schema for tunables

> **Revision 4 — this schema is now the SAME schema `design/01` §9.6 publishes.** Revision 3 published twelve topics derived from a twelve-value `GainId`; `design/01` §9.6 published fifteen flat topics including `jerk`, `velocityTolerance`, `goalDebounceSeconds`, `manualDeadband` and `manualScale`, which revision 3's `ProfileConstraints` could not even represent. Two documents cannot both be right about one NT table, and the shipped Elastic layout, `TunedValueStore`'s file format and `TunableGains` are all built on it. **`design/01`'s flat list wins** (it is the receiving document and it already enumerates the ControlConfig side), extended with the two integral topics D1a moved off `Gains`.

`/Tuning/<Mechanism>/` contains **exactly seventeen editable doubles plus the setpoint subtable.** Seven are `Gains` components in **volts-per-SI**, written through the `GainSink`; ten are `ControlConfig` values in **user units**, written by the mechanism layer. Nothing else lives here.

**"`<Mechanism>`" is a scope, not a wildcard, and `NtSchemaTest` reads it that way.** The seventeen-double rule binds a namespace that is some registered `TuningTarget.tuningName()`. The general-purpose primitives — `tunable(...)` and `tunableFlag(...)` (§5.2) — publish at `/Tuning/<namespace>/<key>` for namespaces that are *not* mechanisms (`design/03`'s `Vision`, `design/05`'s `Drive`), which is the same allowlisted-namespace model **D11** already requires, and those keys are outside this table by construction. **A `tunableFlag` whose namespace collides with a registered mechanism name is refused** at `tunableFlag(...)` with a FATAL `ConfigError` through the same collected-never-thrown path as everything else in §5.2 — otherwise a boolean would appear directly under `/Tuning/<Mechanism>/`, which is exactly what `NtSchemaTest` asserts is impossible, and the test would be right to fail.

```
--- Gains (7): volts-per-SI, owned by TunableGains -> GainSink.  topic == GainId.key() ---
/Tuning/<Mechanism>/kP                        double   GainId.KP     V/m   or V/rad
/Tuning/<Mechanism>/kI                        double   GainId.KI     V/(m*s) or V/(rad*s)
/Tuning/<Mechanism>/kD                        double   GainId.KD     V/(m/s) or V/(rad/s)
/Tuning/<Mechanism>/kS                        double   GainId.KS     V
/Tuning/<Mechanism>/kV                        double   GainId.KV     V/(m/s) or V/(rad/s)
/Tuning/<Mechanism>/kA                        double   GainId.KA     V/(m/s^2) or V/(rad/s^2)
/Tuning/<Mechanism>/kG                        double   GainId.KG     V

--- ControlConfig (10): USER units, owned by design/01's mechanism layer -------------------
/Tuning/<Mechanism>/iZone                     double   user units      (D1a: ControlConfig.integral)
/Tuning/<Mechanism>/iMaxVolts                 double   V               (D1a: ControlConfig.integral)
/Tuning/<Mechanism>/maxVelocity               double   user units/s    (MotionConstraints)
/Tuning/<Mechanism>/maxAcceleration           double   user units/s^2  (MotionConstraints)
/Tuning/<Mechanism>/jerk                      double   user units/s^3  (MotionConstraints)
/Tuning/<Mechanism>/tolerance                 double   user units      (D1b: ControlConfig owns it)
/Tuning/<Mechanism>/velocityTolerance         double   user units/s
/Tuning/<Mechanism>/goalDebounceSeconds       double   s
/Tuning/<Mechanism>/manualDeadband            double   0..1
/Tuning/<Mechanism>/manualScale               double   0..1

--- Setpoints (DESIGN.md section 5.6): persisted through TunedValueStore, keyed by RobotId ---
/Tuning/<Mechanism>/Setpoints/<NAME>          double   user units
```

**`jerk` survives** (`DESIGN.md` §12 open question 4, answered here in the direction that requires no change to `design/01`): `MotionConstraints` is D2's canonical constraint type and it has a `jerk` component, so the topic exists and the seam must convert it. **Cross-doc action (§19):** `design/01`'s Phoenix backend passes `MotionMagicJerk = constraints().jerk()` *unconverted* while cruise and acceleration on the adjacent lines are converted — off by 360 for a rotary axis and by 1/travel-per-rotation for a linear one. `MechanismUnits` needs a `toOutputRps3(double)` and both call sites need it, with a jerk row added to `UnitsContractTest`.

**`setpoint` is not a gain, and neither are the LQR sliders.** `setpoint` is a poke target — it commands motion, `Gains` cannot hold it, and `TunableGains.checkAndApply()` has nothing to do with it. The two `LqrSuggestStep` sliders are *student preferences*: inputs to a solver, never written to a motor controller, never persisted in the `gains` block of `gains.json`. All three live outside `/Tuning`:

```
/RootstockTuner/<Mechanism>/setpoint            double   (manual poke target; the wizard also drives it)
/RootstockTuner/<Mechanism>/lqr/maxError        double   (section 9.2 slider, SI)
/RootstockTuner/<Mechanism>/lqr/maxVolts        double   (section 9.2 slider, V)
```

`TunableGains` builds its seven-topic list by iterating `GainId.values()`, so the gain half of the schema above is **generated, not typed**. `NtSchemaTest` (§16.2) asserts the two sets are equal in both directions: every `GainId` has a topic and every topic directly under `/Tuning/<Mechanism>/` maps either to a `GainId` that `Gains.with(...)` and `Gains.get(...)` both handle, or to a declared `ControlConfig` tunable. A gain that can be published but not applied is the exact failure this test exists to make impossible. **`NtSchemaTest` is a cross-document test:** it asserts the topic list above is byte-identical to the one `design/01` §9.6 documents, so the two specifications cannot drift again.

Metadata lives **outside** `/Tuning`, as one JSON string per mechanism, so it never clutters the tuning tab:

```
/RootstockTuner/Mechanisms/<Mechanism>/meta     string (JSON)
```

```json
{
  "name": "Elevator",
  "archetype": "ELEVATOR",
  "siDomain": "LINEAR_METERS",
  "controlLocation": "ON_MOTOR_PROFILED",
  "controlLocationSource": "DEFAULTED",
  "gravityMode": "CONSTANT",
  "travelLimits": { "min": 0.0, "max": 1.397, "softMargin": 0.02794 },
  "gains": {
    "kP": { "unit": "V/m",       "min": 0.0, "max": 400.0, "step": 0.5,    "source": "WIZARD_REFINE" },
    "kG": { "unit": "V",         "min": 0.0, "max": 6.0,   "step": 0.0005, "source": "WIZARD_BISECTION" },
    "kV": { "unit": "V/(m/s)",   "min": 0.0, "max": 20.0,  "step": 0.01,   "source": "WIZARD_OLS" },
    "kA": { "unit": "V/(m/s^2)", "min": 0.0, "max": 5.0,   "step": 0.001,  "source": "WIZARD_OLS" }
  },
  "supervisorBand": { "positionMin": 0.06985, "positionMax": 1.32715 },
  "conversion": "1 mechanism rotation = 0.2794 m; kP 128.000 V/m -> Slot0.kP 35.7632",
  "libraryVersion": "0.1.0",
  "wpilibVersion": "2026.2.2"
}
```

`source` is one of `CODE_DEFAULT`, `DEPLOY_FILE`, `ROBOT_FILE`, `DASHBOARD`, `WIZARD_OLS`, `WIZARD_BISECTION`, `WIZARD_LQR`, `WIZARD_REFINE`. It is what makes "where did this number come from?" answerable from the dashboard alone, which is the debuggability bar the small-team research sets.

### 5.6 Interaction with AdvantageKit replay

This is the subtle part and it must be right, because the user's own template
(`0000-XXXX-Robot-Template`) makes deterministic replay a
non-negotiable architectural constraint — and under maintainer decision 3 it is a **guarantee of the
library**, not a property of what a team happened to install.

**The hazard.** A tunable read from NetworkTables is an *input* to robot code. AdvantageKit replays inputs from the log; anything read outside a logged-input channel is not replayed, so a replay run silently uses the deploy-time default while the real run used a dashboard value. Outputs diverge, with no error.

**The fix: one poller, one `LoggableInputs` struct, one `processInputs` call.**

```java
package org.rootstock.tuning;

/** How a tunable's value crosses from the dashboard into robot code, replay-safely. */
public interface TunableTransport {

  /** Register a topic and its default. */
  Handle register(String fullKey, double defaultValue);

  interface Handle {
    double get();
    void set(double value);
  }

  /** Drain the wire once. Called from the priority-30 {@code LifecycleHook}, exactly once per loop. */
  void drain();

  /** Human-readable name shown in the boot log and the UI. */
  String describe();
}
```

One implementation ships; one more is named and reserved:

| Implementation | When selected | Replay behavior |
|---|---|---|
| `AdvantageKitTunableTransport` | **Always, unconditionally** (maintainer decision 3). Referenced at compile time — no reflection, no probe, no fallback. | `drain()` performs one `readQueue()` in real mode, writes every changed value into a single hand-written `TuningInputs implements LoggableInputs`, and calls `Logger.processInputs("Tuning", inputs)` once. **In replay, `processInputs` overwrites the struct from the log and the poller is never read**, so every tunable reproduces the exact dashboard value that was live at the time. Fully replay-safe, and **guaranteed** rather than dependent on what a team installed. |
| `WpilibTunableTransport` | Reserved for WPILib's first-party `Tunable` API if and when it merges — see §5.7 and the annual relevance review in [`ROADMAP.md`](../ROADMAP.md) §7.3. Not built today. | To be determined by that API. |
| ~~`Nt4TunableTransport`~~ | ~~Default; AdvantageKit not on the classpath~~ | **DELETED by maintainer decision 3.** There is no classpath without AdvantageKit. It existed only to serve the case decision 3 removed, and keeping it would mean shipping a second, untested, replay-unsafe path. *(This row is history, deliberately preserved. Appendix A no longer lists the class.)* `[SUPERSEDED-NAME]` |

`TuningInputs` is one struct for the whole table, hand-written per **D24** (`@AutoLog` is banned library-wide, because it generates into the annotated type's own package):

```java
package org.rootstock.tuning;

import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * Every tunable in the robot, as ONE logged input. Two parallel arrays rather than a map, because
 * LogTable stores primitives and arrays and the pairing must be positional to round-trip.
 * Keys are sorted once at first publish and never reordered, so the wire layout is stable and a
 * replay of an older log against newer code is diagnosable rather than silently misaligned.
 */
final class TuningInputs implements LoggableInputs {
  String[] keys   = new String[0];    // full NT keys, e.g. "/Tuning/Elevator/kP", sorted
  double[] values = new double[0];

  // Flags (TuningRegistry.tunableFlag, design/03 contract C8) round-trip in their own pair of
  // arrays rather than as 0.0/1.0 in the doubles above: a boolean stored as a double is a boolean
  // a replay can silently widen, and AdvantageScope would render a pit kill switch as a number.
  String[] flagKeys   = new String[0];    // e.g. "/Tuning/Vision/camera0Enabled", sorted
  boolean[] flagValues = new boolean[0];

  @Override public void toLog(LogTable table) {
    table.put("Keys", keys);
    table.put("Values", values);
    table.put("FlagKeys", flagKeys);
    table.put("FlagValues", flagValues);
  }

  @Override public void fromLog(LogTable table) {
    keys        = table.get("Keys", keys);
    values      = table.get("Values", values);
    flagKeys    = table.get("FlagKeys", flagKeys);
    flagValues  = table.get("FlagValues", flagValues);
  }
}
```

> **Reviewer pushback:** the routed finding asks §2.3 to read *"required (maintainer decision 3); `LoggedNetworkNumber` referenced at compile time."*
> **Why we keep AdvantageKit Required but change the named class:** AdvantageKit **is** moved into the Required list exactly as the finding asks, and the "optional / reflectively detected" wording is deleted. But `LoggedNetworkNumber` cannot be the mechanism. Its `periodic()` is documented as *"Updates internal value from NetworkTables (unless in replay mode) and processes inputs"* — **one NT read and one `processInputs` per instance, per loop** (verified 2026-08-08 against the source: `LoggedNetworkNumber(String key)`, `LoggedNetworkNumber(String key, double defaultValue)`, `double get()`, `void set(double)`, `void setDefault(double)`, `void periodic()`, `double getAsDouble()` — https://github.com/Mechanical-Advantage/AdvantageKit, `akit/src/main/java/org/littletonrobotics/junction/networktables/LoggedNetworkNumber.java`). Binding **D11a(b)** requires *one* JNI call per loop **regardless of tunable count**, and explicitly names the ~250-reads-per-loop model it replaced. One `LoggedNetworkNumber` per tunable is that model wearing a different hat. The design above keeps **both** properties — one queue drain and one `processInputs` — by owning the struct instead of the wrapper, which is also what **D9** already requires of every other IO layer in the library ("IO layers implement `LoggableInputs` **directly**"). The compile-time AdvantageKit references in this domain are therefore `Logger`, `LogTable` and `LoggableInputs`, plus `LoggedRobot` reached through `RootstockRobot`.

Rules the implementation must obey:

1. **Never call `Timer.getFPGATimestamp()` or `Timer.getTimestamp()`.** All timestamps come from `org.rootstock.core.compat.Clock.seconds()` and all periods from `Clock.dt()`, so unit tests and replay can drive time. *(Revision 3 said "`Timer.getTimestamp()`"; `DESIGN.md` §16 item 3 requires the facade — domain 06's `volatileApiIsConfined` rule is red otherwise.)*
2. **No `HashMap` iteration-order dependence** in anything that produces an output. `TuningRegistry` stores tunables in a `LinkedHashMap` keyed by full NT key and iterates in insertion order; `TuningInputs.keys` is sorted once and frozen.
3. **No `Math.random()`, no background threads.** Sweeps are driven from the main loop; the OLS accumulation happens inline; every wizard step is a `begin`/`periodic`/`isComplete` state machine (§8.1). **This rule is why §8.4's bisection is written as an explicit sub-state machine and not as a `while` loop with a `yieldOneLoop()` — see §8.4.**
4. **The wizard refuses to arm in REPLAY mode** and publishes `state = "DISABLED_REPLAY"`. Actuating a mechanism during a replay is nonsensical; re-running the *fit* against replayed data is a feature we expose separately (§8.8, offline refit).
5. ~~If AdvantageKit is on the classpath but the plain NT transport was forced, raise a warning alert.~~ **DELETED by maintainer decision 3** — there is no plain-NT transport to force and no classpath without AdvantageKit, so there is nothing to warn about. The alert existed to make a *degraded* configuration visible; the degraded configuration no longer exists.

### 5.7 The WPILib 2027 `Tunable` API

**Verified 2026-08-06:** `wpilibsuite/allwpilib` PR **#7773, "[telemetry] Add Telemetry and Tunable APIs"** by PeterJohnson is **open, not merged**, targeting the **2027 Alpha 7** milestone. It introduces `Tunable<T>`, a `TunableRegistry`, and backends including `NetworkTablesTunableBackend` and `DataLogTunableBackend`, publishing under a configurable prefix (default `/Tunables`), with `onTune` callbacks. Reviews as of the fetch date flag unresolved concerns about mutex handling during callback execution. Exact final class and method names are therefore **[UNVERIFIED]** and must not be coded against.

**Decision: we plan for it, we do not depend on it, and we do not race it.**

1. `TunableTransport` (§5.6) is the seam. When WPILib 2027 ships a merged Tunable API, we add a **second** implementation, `WpilibTunableTransport`, and select it by default after the M12 port. `TunableDouble`'s public surface does not change.
2. We keep publishing at `/Tuning/<Mechanism>/<gain>` through M11, because AdvantageScope's tuning mode reads `/Tuning` today. After M12 we publish to **both** paths for one season (WPILib's path as source of truth, `/Tuning` as a read-only mirror) and drop the mirror the season after.
3. We do **not** attempt source compatibility with an unmerged PR.
4. If the PR merges before v0.1 ships — which, on a 74.0 pw plan landing in 2029–2030 at solo pace, is now **likely rather than speculative** — this section is the thing that changes, and the question is asked formally at each kickoff by the annual relevance review ([`ROADMAP.md`](../ROADMAP.md) §7.3, risk R20). If WPILib's first-party Tunable API lands and is good, the honest response is to sit on top of it, not to defend `TunableDouble` because we wrote it.

### 5.8 Performance budget

Loop overruns were attributed to competing libraries repeatedly in 2026, and at least one team's response was to delete their telemetry entirely. The tuning layer must be measurably free:

| Path | Budget | How |
|---|---|---|
| Tuning disabled (FMS) | **< 5 µs/loop total**, zero allocation | One boolean check in the priority-30 hook; early return; the poller is never read. |
| Tuning enabled, nothing changed | **< 20 µs/loop, for ANY number of tunables** | **One** `NetworkTableListenerPoller.readQueue()` JNI call returning an empty array, one array-length check, one `Logger.processInputs("Tuning", inputs)`. Not 144 `DoubleEntry.get()` calls — that was revision 1's model, superseded by D11a(b). |
| Tuning enabled, one gain changed | **< 400 µs** on the changing loop | One `HashMap` lookup per queued event, one `double` field write, one `Gains` record allocation, one `GainSink.apply()` on the `"Tuning"` slice, rate-limited to 10 Hz per mechanism. |
| Wizard running a sweep | **< 150 µs/loop** | 4 multiply-adds per regression column (at most 20 fused ops), one ring-buffer write, one batched NT publish. |

**The one allocation on the enabled path, named.** `readQueue()` returns a `NetworkTableEvent[]`, which is an allocation per loop even when empty. That is why `TuningAllocationTest` asserts **zero** allocations on the **disabled** path (where the poller is never touched) and a **bounded, constant** allocation on the enabled path — one small array, independent of tunable count. Revision 3's budget row claimed zero allocation for the enabled steady state, which was true of the per-entry model and is not true of the poller model; the poller model is still strictly cheaper, and this is the honest statement of what it costs.

Enforced by `TuningAllocationTest` and `LoopTimingTest` (steady-state budget on the CI container), and by `RootstockTracer`'s per-domain loop-time budget for the `Tuning` slice (design/06 §12.6).

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
| Generating the two callbacks from a mechanism declaration | **Rootstock** (`SysIdSweep.routineFor(target)`) |
| Deriving safe ramp rate, step voltage and timeout from soft limits | **Rootstock** (§6.2) — WPILib's 1 V/s, 7 V, 10 s defaults are unsafe on a 1.4 m elevator |
| Safety aborts during the sweep | **Rootstock** (`TuningSupervisor`, §7) — WPILib states explicitly that the routine only creates voltage commands and limits are your problem |
| Fitting kS/kV/kA/kG | **Rootstock** (streaming OLS, §6.3), *in addition to* writing the WPILog |
| Log hygiene (one routine per file, auto-named) | **Rootstock** (§6.6) |
| Off-robot analysis in the SysId GUI | **WPILib SysId**, still fully supported as an escape hatch |

Nothing is taken away. A team that wants the official tool gets a clean, correctly-named, single-routine WPILog with no extra effort. A team that does not want a laptop gets the gains on the dashboard 20 seconds after the sweep ends.

### 6.2 `SysIdSweep` — generated routine with a derived envelope

```java
package org.rootstock.tuning.sysid;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Voltage;
import edu.wpi.first.units.measure.Velocity;
import edu.wpi.first.units.VoltageUnit;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.sysid.SysIdRoutine;
import org.rootstock.control.SafetyEnvelope;
import org.rootstock.control.TuningTarget;

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

Let `L = envelope.positionMax() - envelope.positionMin()` be the usable travel **inside the supervisor band** (meters or radians), `Vmax = envelope.maxVolts()` the voltage ceiling, and `kVprior`, `kAprior` the values implied by `PlantPrior` (§6.5).

```
// Predicted steady-state speed at the step voltage:
vStep      = (Vstep - kSprior) / kVprior

// POSITION archetypes (ELEVATOR, ARM, TURRET, STEER):
//   the quasistatic ramp must not consume more than 70% of the usable band before hitting Vmax.
//   Time to reach Vmax at rate r is Vmax/r; distance covered is roughly the integral of v(t):
//       d(r) = (Vmax^2) / (2 * r * kVprior)
//   Solve d(r) = 0.70 * L for r:
rampRate   = clamp( Vmax^2 / (2 * 0.70 * L * kVprior),  0.25,  2.0 )    // V/s

//   the dynamic step must not consume more than 45% of the band before the timeout.
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

> **Revision 4:** revisions 1 through 3 derived `L` from `limits().softMax() - limits().softMin()`. That is the *device's* soft-limit band, which is strictly **wider** than the supervisor's (§7.1), so a ramp sized against it can plan to travel past the point where the supervisor will abort. Deriving from the envelope is the only self-consistent choice, and it is what `SysIdEnvelopeTest` asserts.

`SysIdRoutine.Config` is then constructed with the verified signature
`Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout, Consumer<SysIdRoutineLog.State> recordState)`,
where the fourth argument is Rootstock's own state consumer that (a) forwards to `SysIdRoutineLog` so the WPILog stays valid, and (b) drives the regression's phase tracking.

The `Mechanism` is constructed with the verified signature
`Mechanism(Consumer<Voltage> drive, Consumer<SysIdRoutineLog> log, Subsystem subsystem, String name)`:

```java
new SysIdRoutine.Mechanism(
    v -> { supervisor.commandVolts(v.in(Volts)); },       // never target.setVoltage() directly
    log -> {
      var motor = log.motor(target.tuningName());
      motor.voltage(Volts.of(appliedVolts()));
      if (target.siDomain() == SiDomain.LINEAR_METERS) {
        motor.linearPosition(Meters.of(target.measuredSi()))
             .linearVelocity(MetersPerSecond.of(target.velocitySi()));
      } else {
        motor.angularPosition(Radians.of(target.measuredSi()))
             .angularVelocity(RadiansPerSecond.of(target.velocitySi()));
      }
      regression.add(target.measuredSi(), target.velocitySi(), accel(), appliedVolts());
    },
    target.requirement().orElse(dummySubsystem),
    target.tuningName());
```

Note `appliedVolts()` prefers `TuningTarget.appliedVolts()` (the device's measured output) over the commanded value. On a sagging battery those differ by half a volt, and fitting against the commanded value biases kV high — one of the most common silent characterization errors.

### 6.3 The least-squares fit — matrix formulation

The mechanism models, exactly as SysId uses them (`u` = applied volts, `x` = position, `v` = velocity, `a` = acceleration, `theta` = angle **from horizontal**, i.e. `measuredSi() - horizontalReferenceSi()`):

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

> **[UNVERIFIED]** This voltage-prediction R^2 is **not** the same statistic as SysId's reported "simulated velocity r^2" or "acceleration r^2", and the two are not directly comparable. SysId's thresholds (simulated-velocity r^2 > 0.9 good; acceleration r^2 rarely above 0.5) do not transfer. Rootstock therefore reports its own metric with its own thresholds and labels it clearly as `voltageFitR2`, and additionally computes a *simulated-velocity* R^2 from the decimated replay buffer (§6.5) so a student who knows SysId sees a familiar number too.

### 6.4 `FeedforwardRegression` — the implementation

> **Package note.** `FeedforwardRegression` is HAL-free and lives in **`org.rootstock.pure.solvers`** (`DESIGN.md` §7 package tree; the zero-`edu.wpi.first`-import rule is enforced by bytecode scan at the package level). It is re-exported for readability under `org.rootstock.tuning.sysid` only in the sense that the sysid package is its only caller; there is one class.

```java
package org.rootstock.pure.solvers;

import org.rootstock.control.MechanismArchetype;

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
   * @param angleFromHorizontal radians from horizontal, for ARM only; ignored otherwise
   * @param velocity     m/s or rad/s
   * @param acceleration m/s^2 or rad/s^2. NaN samples are skipped for the kA column only.
   * @param volts        APPLIED volts, preferably as measured by the device
   */
  public void add(double angleFromHorizontal, double velocity, double acceleration, double volts) {
    if (!Double.isFinite(volts) || !Double.isFinite(velocity)) return;
    double a = Double.isFinite(acceleration) ? acceleration : 0.0;

    int k = 0;
    if (archetype == MechanismArchetype.ELEVATOR)  phi[k++] = 1.0;
    if (archetype == MechanismArchetype.ARM)       phi[k++] = Math.cos(angleFromHorizontal);
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

> **Revision 4 — the ARM regressor now takes the angle *from horizontal*, not the raw position.** Revisions 1 through 3 passed `position` and computed `Math.cos(position)`, which is correct only when the mechanism's zero happens to be horizontal. `design/01`'s `RotaryAxis.arm(Angle horizontalAt)` has carried the real offset since revision 2, and `TuningTarget.horizontalReferenceSi()` (§3.1) exposes it. Passing the raw position made the `cos` wrong at every angle for any arm whose zero is not horizontal — the exact landmine `0000-XXXX-Robot-Template/Constants.java` documents ("Arm_Cosine is only correct when 0 deg = arm horizontal") and which §8.8's three-angle check exists to detect. Now the check detects a *residual* error rather than the design's own omission.

**`FitFailure`, and why it is not `AbortReason`.**

```java
package org.rootstock.pure.solvers;

/** Why a fit could not be produced. Distinct from AbortReason, which is why MOTION stopped. */
public enum FitFailure { INSUFFICIENT_DATA, RANK_DEFICIENT }

public final class IdentificationException extends RuntimeException {
  public IdentificationException(FitFailure failure, String message) { /* ... */ }
  public FitFailure failure() { /* ... */ return null; }
}
```

Revisions 1 through 3 threw `IdentificationException(AbortReason.INSUFFICIENT_DATA, ...)`, which put two fit outcomes into the enum [`ROADMAP.md`](../ROADMAP.md) M7 gates on ("all 12 abort conditions") and made the count unreconcilable. `AbortReason` is now exactly twelve motion aborts (§7.2); fit failures have their own two-value enum, and `SafetyAbortTest`'s "one test per `AbortReason`" is twelve tests.

`solve()` body, in full:

```java
public FeedforwardFit solve() {
  if (samples < 200) {                              // 4 s at 50 Hz
    throw new IdentificationException(FitFailure.INSUFFICIENT_DATA,
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
      throw new IdentificationException(FitFailure.RANK_DEFICIENT,
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

> **The one WPILib import in `org.rootstock.pure`, and how it is avoided.** `MatBuilder`/`VecBuilder`/`Matrix` are `edu.wpi.first.math` types, which the pure package forbids. The solver core therefore takes a `LinearSolver` functional interface — `double[] solve(double[][] A, double[] b)` — and `org.rootstock.tuning.sysid` supplies the wpimath-backed implementation above. `PurePackageScanTest` (design/06) fails the build on any `edu.wpi.first` reference inside `org.rootstock.pure`, so this is enforced rather than remembered.

> **Verified WPILib signatures used above:**
> `MatBuilder.fill(Nat<R> rows, Nat<C> cols, double... data)` (row-major),
> `VecBuilder.fill(double...)` up to `N10`,
> `Matrix.solveFullPivHouseholderQr(Matrix<R2,C2> other)` returning `Matrix<C,C2>`,
> `Matrix.get(int row, int col)`.
> Note that `Matrix` has **no `plusEqu`** method, which is why accumulation is done in plain `double[][]` rather than in `Matrix` objects — that is also allocation-free, which `Matrix.plus` would not be.

`FeedforwardFit`:

```java
package org.rootstock.tuning.sysid;

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

**Quality thresholds** (Rootstock's own, chosen to be conservative; not from a WPILib source, so labeled as ours in the UI):

| `voltageFitR2` | `rmseVolts` | Quality | UI text |
|---|---|---|---|
| >= 0.95 | <= 0.25 | `GOOD` | "Good fit. The model explains 97% of the voltage you applied." |
| >= 0.85 | <= 0.50 | `ACCEPTABLE` | "Usable fit, but noisy. Re-run with a slower ramp if the gains look odd." |
| >= 0.60 | any | `SUSPECT` | "Poor fit. Usually this means backlash, a slipping encoder, or something else fighting the motor." |
| < 0.60 | any | `UNUSABLE` | "This fit is not trustworthy and Rootstock will not accept it. Run the mechanical health check." |

### 6.5 Sanity-bounding the fit against physics

Numbers can be statistically excellent and physically absurd. Before a fit is offered to the student, each gain is compared with the prior implied by `PlantPrior`:

```
// Prior from the motor curve. For a mechanism with reduction G = plantPrior.reduction().rotorPerOutput()
// and either mass m (linear, effective radius r) or inertia J (rotational):
//
// In practice we get kV and kA directly from wpimath rather than deriving them by hand:
LinearSystem<N2,N1,N2> plant = switch (archetype) {
  case ELEVATOR ->
      LinearSystemId.createElevatorSystem(motor, massKg, radiusMetres, G);
  case ARM, TURRET, STEER ->
      LinearSystemId.createSingleJointedArmSystem(motor, moiKgM2, G);
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
| `kG` sign (ELEVATOR, ARM) | `signum(kG)` must equal `gSign` from §8.4 step 0 | `"kG came out with the opposite sign to the direction this mechanism falls. Check which way your encoder counts."` |
| `kG` magnitude (ARM) | within `[0.3x, 3.0x]` of `kGprior` | warning |
| `kG` magnitude (ELEVATOR) | within `[0.3x, 3.0x]` of `kGprior` | warning |

> **On the sign of kG.** The old form of this check hard-coded `kG >= 0`, which silently assumed "positive position is up." It is not: a hood or a wrist whose positive direction points *downward* has a genuinely negative kG, passes the `MechanicalHealthCheck` direction test (positive voltage really does produce positive position change), and would have been handed `kG = 0` for a mechanism that visibly sags. The sign is now **measured**, once, at the start of `HoldBisectionStep`, and every downstream check compares against that measurement instead of an assumption. `kGprior` is always reported as a positive magnitude; the sign comes from the measurement.

#### `kGprior`, stated explicitly — and the factor-of-n bug it had

§8.4 brackets the bisection from `kGprior` and §7.6 perturbs it, so this number being n× low is not a cosmetic problem: it is bracket exhaustion, reported as `RETRY_SUGGESTED` blaming the team's `PlantPrior`, on a perfectly healthy two-motor mechanism.

```
// Volts required to hold the gravity load at the worst-case pose, from the motor curve.
//   tau_gravity      = m * g * L        (ARM: L = centre-of-mass length)
//   tau_gravity      = m * g * r        (ELEVATOR: r = EFFECTIVE radius, including cascade rigging)
//   torquePerVolt    = motor.KtNMPerAmp / motor.rOhms      // for the WHOLE gearbox, see below
//   G                = plantPrior.reduction().rotorPerOutput()
//
kGprior = tau_gravity / (G * motor.KtNMPerAmp / motor.rOhms)
```

> **Revision 4 — the correction, with the source.** Revisions 1 through 3 wrote
> `kGprior = tau_gravity / (gearingReduction * motor.KtNMPerAmp / motor.rOhms * motorCount)`
> and then said, in the very next sentence, that *"`DCMotor.getKrakenX60Foc(n)` already folds `motorCount` into them."* Both cannot be true, and the second one is. **Verified 2026-08-08 against WPILib's `DCMotor` source** (`wpimath/src/main/java/edu/wpi/first/math/system/plant/DCMotor.java`, https://github.com/wpilibsuite/allwpilib):
>
> ```java
> public DCMotor(double nominalVoltageVolts, double stallTorqueNewtonMeters,
>                double stallCurrentAmps, double freeCurrentAmps,
>                double freeSpeedRadPerSec, int numMotors) {
>   this.stallTorqueNewtonMeters = stallTorqueNewtonMeters * numMotors;
>   this.stallCurrentAmps        = stallCurrentAmps * numMotors;
>   this.freeCurrentAmps         = freeCurrentAmps * numMotors;
>   this.rOhms                   = nominalVoltageVolts / this.stallCurrentAmps;
>   this.KvRadPerSecPerVolt      = freeSpeedRadPerSec / (nominalVoltageVolts - rOhms * this.freeCurrentAmps);
>   this.KtNMPerAmp              = this.stallTorqueNewtonMeters / this.stallCurrentAmps;
> }
> public static DCMotor getKrakenX60Foc(int numMotors) {
>   return new DCMotor(12, 9.37, 483, 2, Units.rotationsPerMinuteToRadiansPerSecond(5800), numMotors);
> }
> ```
>
> Stall torque and stall current both scale by `n`, so **`KtNMPerAmp` is unchanged by `n`** (the ratio cancels) while **`rOhms` is divided by `n`**. Therefore `KtNMPerAmp / rOhms` **already carries the ×n**, and it simplifies exactly:
>
> ```
>   Kt / R = (tau_stall_1 / I_stall_1) * (I_stall_1 * n / V_nom) = tau_stall_1 * n / V_nom
> ```
>
> The explicit `÷ motorCount` therefore made `kGprior` **n times too low on every multi-motor mechanism.** On a two-motor arm the bracket `hi = 1.8 * kGprior` sits at `0.9 * kG_true` — *below* the true value — so all ten iterations walk to the ceiling and the step reports `RETRY_SUGGESTED` blaming the mass or the gear ratio the team declared, on hardware that is fine. The `[0.3x, 3.0x]` sanity band in the table above warns spuriously for the same reason.
>
> **`PlantPrior.motor` must be constructed with the real motor count.** That is now stated on the record itself (§3.1) and pinned by `HoldBisectionSafetyTest`'s two-motor `SingleJointedArmSim` case, which asserts the bracket `[0.2·kGprior, 1.8·kGprior]` **contains** the true kG.

**Worked, on this document's elevator** (2 × Kraken X60 FOC, 12:1, 24 lb, effective radius 0.0444679 m — §4.2):

```
motor  = DCMotor.getKrakenX60Foc(2)
         stallTorque 9.37 x 2 = 18.74 N.m      stallCurrent 483 x 2 = 966 A
         rOhms       = 12 / 966          = 0.01242236 ohm
         KtNMPerAmp  = 18.74 / 966       = 0.0194000 N.m/A          (unchanged by n -- see above)
         Kt / R      = 0.0194000 / 0.01242236 = 1.561667 N.m/V      (= 18.74 / 12, exactly)
         Kv          = 607.375 / (12 - 0.01242236 x 4) = 50.8251 rad/s/V

tau_gravity = m * g * r = 10.886217 kg x 9.80665 m/s^2 x 0.0444679 m
            = 106.75655 N x 0.0444679 m = 4.747273 N.m

kGprior     = 4.747273 / (12 x 1.5616667) = 4.747273 / 18.74 = 0.253323 V   <- correct
               (revisions 1-3 printed 4.747273 / (18.74 x 2) = 0.126661 V)  <- n times low

kVprior     = 1 / ((Kv / G) * r) = 1 / ((50.825004 / 12) x 0.0444679)
            = 1 / 0.1883401 = 5.309545 V/(m/s)
freeSpeedSi = 12 x 0.1883401 = 2.260081 m/s        (design/01 prints 2.26 m/s -- same plant)
kAprior     = R * r * m / (G * Kt)
            = 0.01242236 x 0.0444679 x 10.886217 / (12 x 0.0193996)
            = 0.00601366 / 0.2327952 = 0.0258318 V/(m/s^2)
```

Every one of those five lines is asserted by `PlantPriorDerivationTest` against `DCMotor.getKrakenX60Foc(2)` and `LinearSystemId.createElevatorSystem`, to 1e-6 relative.

### 6.6 Log hygiene

WPILib is explicit: *"Only log files with a single routine in them are usable for analysis."* Running sequential routines without extracting or power-cycling causes analysis failure. Rootstock owns this so a student cannot get it wrong:

1. `SysIdSweep.fullSweep()` calls `DataLogManager.start()` if not already started, then closes the current log and starts a **new** one named `sysid-<Mechanism>-<yyyyMMdd-HHmmss>.wpilog` before the first test.
2. Between the four tests it inserts a `settle` command (mechanism neutral, 0.75 s) and, for position archetypes, a return-to-start move. No second routine is written to the same file.
3. At the end of the sweep the file is closed and its path is published to `/RootstockTuner/lastSysIdLog` (string) so a student can find it with FTP/scp without guessing.
4. The UI always shows: `"AdvantageKit logs are not directly loadable by SysId. Rootstock wrote a separate plain WPILog for you at /U/logs/sysid-Elevator-20260808-141233.wpilog."` — *always*, not "when AdvantageKit is detected", because under maintainer decision 3 there is no configuration in which it is absent.
5. If `/U` is not mounted (no USB stick), we log to `Platform.persistentDir() + "/logs"` and raise `Alerts.warning("Tuning", ..., MatchImpact.PIT_ONLY)` rather than failing. A robot that will not run because logging failed is a lost match.

---

## 7. Safety — `TuningSupervisor`

Every existing FRC live-tuning implementation ships with a documentation warning and no interlocks. YAMS: *"Live Tuning can be DANGEROUS please test in sim before the real robot."* WPILib SysId: *"it is up to you to set up hard or soft limits to prevent injury or damage."* FrcCatalyst ships tuning enabled by default. **Rootstock makes safety structural instead of documentary.** This is the single strongest differentiator for a library aimed at teams where no mentor is watching.

### 7.1 `SafetyEnvelope`

```java
package org.rootstock.control;

import java.util.List;
import org.rootstock.config.ConfigError;
import org.rootstock.units.SiDomain;

/** Every actuating tuning routine runs inside one of these. Derived from the target; overridable. */
public record SafetyEnvelope(
    double maxVolts,              // absolute voltage ceiling. Default 0.85 * nominal.
    double positionMin,           // hard abort band, STRICTLY inside the device soft limits
    double positionMax,
    double maxAbsVelocity,        // abort above this
    double maxStatorAmps,         // abort above this, sustained for holdoffSeconds
    double holdoffSeconds,        // current must exceed the limit for this long (default 0.15)
    double maxRoutineSeconds,     // wall-clock timeout for the whole routine
    double stallVoltsThreshold,   // "commanded this many volts"
    double stallVelocityThreshold,// "...but moving slower than this"
    double stallSeconds,          // "...for this long" -> STALLED
    boolean requireHeldEnable) {

  /** Extra guard beyond the team's margin, as a fraction of TRAVEL. */
  public static final double GUARD_FRACTION_OF_TRAVEL = 0.03;
  /** Absolute floor on that extra guard, so a very short axis still gets a real band. */
  public static final double MIN_GUARD_METRES  = 0.005;
  public static final double MIN_GUARD_RADIANS = 0.020;

  /**
   * Derive from the mechanism. This is what the wizard uses; teams rarely construct one by hand.
   *
   * <ul>
   *   <li>{@code positionMin/Max} are pulled in from the <b>hard</b> limits by
   *       {@code softMargin + max(0.03 * range, floor)} — the team's margin <b>plus</b> a travel-derived
   *       guard, never the maximum of the two — so the supervisor band is <b>strictly</b> inside the
   *       device's soft-limit band for every legal margin. The student always sees "Rootstock
   *       stopped this" instead of a silent device clamp.</li>
   *   <li>{@code maxAbsVelocity} = 1.15 x the free speed predicted by {@link PlantPrior}.</li>
   *   <li>{@code maxStatorAmps} = 0.85 x the configured stator limit, or 60 A if unknown.</li>
   * </ul>
   */
  public static SafetyEnvelope derive(TuningTarget target) {
    TravelLimits t = target.travelLimits();
    double floor = target.siDomain() == SiDomain.LINEAR_METERS ? MIN_GUARD_METRES : MIN_GUARD_RADIANS;
    double guard = t.softMargin() + Math.max(GUARD_FRACTION_OF_TRAVEL * t.range(), floor);
    double positionMin = t.min() + guard;
    double positionMax = t.max() - guard;
    // ... remaining fields as documented above
    return new SafetyEnvelope(/* maxVolts */ 0.85 * target.plantPrior().nominalVolts(),
        positionMin, positionMax, /* ... */);
  }

  /** Collected, never thrown. FATAL when the guard leaves no band at all. */
  public List<ConfigError> validate(String owner, TravelLimits t) {
    if (positionMax > positionMin) return List.of();
    return List.of(new ConfigError(ConfigError.Severity.FATAL, owner, "travelLimits",
        "range " + t.range() + " with softMargin " + t.softMargin(),
        "range > 2 * (softMargin + max(0.03*range, floor))",
        "There is not enough travel here for the tuning supervisor to have a band it can stop "
      + "inside. Either the travel limits are wrong or the soft margin is too large for them.",
        ConfigError.callerFrame()));
  }
}
```

**Why the band is `softMargin + guard` and not `max(softMargin, guard)`.**

The documented guarantee is *"the supervisor always trips before the device's own soft limit clamps silently."* Two different rules have failed it, at opposite ends of the margin range, and the history is worth keeping because the second failure is the first one wearing a different mask:

- **Revision 1** used *"soft limits pulled in by a further 25% of the soft margin."* With the default `softMargin = 0.0` that every `new TravelLimits(min, max, 0.0)` call produced, `0.25 × 0` is zero, the supervisor band was **identical** to the device soft limits, and the guarantee was false in the default case. The failure was silent: the student saw the device clamp with no message.
- **Revision 2** replaced it with `guard = max(softMargin, 0.05 * range)` and added the 2%-of-travel minimum on `TravelLimits`. That fixed the zero-margin end and **broke the other end**: as soon as a team configures a *generous* margin — 0.10 m on 0.8 m of travel, which is 12.5% and is exactly what a careful team does — `max(softMargin, 0.05·range) == softMargin`, `positionMin == min + softMargin == softMin`, and the supervisor band **equals** the device soft-limit band again. The guarantee was false for every margin at or above 5% of travel. `SafetyEnvelopeTest` did not catch it because it only ever exercised margins of exactly `0.02 * range`.

**Revision 4's rule is additive, so the failure is unrepresentable rather than untested:**

```
guard       = softMargin + max(0.03 * range, floor)
positionMin = min + guard
positionMax = max - guard
```

`max(0.03·range, floor) > 0` for every legal `TravelLimits` (`range > 0`, and `floor > 0` unconditionally), so `guard > softMargin` **always**, so `positionMin > softMin` and `positionMax < softMax` **always**. That is a one-line proof rather than a case analysis, which is the point.

**Worked, on this document's elevator** (range 1.397 m, `softMargin` 0.02794 m — §14.1):

```
guard       = 0.02794 + max(0.03 x 1.397, 0.005) = 0.02794 + 0.041910 = 0.069850 m
positionMin = 0.000 + 0.069850 = 0.069850 m       softMin = 0.027940 m   -> 0.041910 m of headroom
positionMax = 1.397 - 0.069850 = 1.327150 m       softMax = 1.369060 m   -> 0.041910 m of headroom
band width  = 1.257300 m  (90.0% of travel)
```

**Non-emptiness.** `TravelLimits` caps `softMargin` at 40% of travel (§3.1). Where the fractional term dominates, `2 * guard = 2(0.40 + 0.03) * range = 0.86 * range < range`, so the band is at least 14% of travel. Where the absolute floor dominates — a linear axis shorter than `0.005 / 0.03 = 0.167 m`, or a rotary axis shorter than `0.020 / 0.03 = 0.667 rad` — a sufficiently short travel *can* run out, and `SafetyEnvelope.validate` emits the FATAL above rather than returning an inverted band.

`SafetyEnvelopeTest` asserts the strict inequality, per archetype, over the full built-in archetype set **and over the whole legal margin range** — which is the specific gap that let the revision-2 regression through:

```java
@ParameterizedTest
@EnumSource(MechanismArchetype.class)
void supervisorBandIsStrictlyInsideDeviceSoftLimits(MechanismArchetype archetype) {
  assumeTrue(archetype.isPosition());
  for (double range : new double[] {0.05, 0.20, 1.60, 6.28}) {
    // Revision 2's test used ONLY 0.02 * range, which is why it passed while the guarantee failed.
    for (double marginFraction : new double[] {0.02, 0.05, 0.10, 0.20, 0.40}) {
      var limits = new TravelLimits(0.0, range, marginFraction * range);
      assertEquals(List.of(), limits.validate("t"), "margin must be legal at " + marginFraction);
      var target = SimTargets.of(archetype, limits);
      var envelope = SafetyEnvelope.derive(target);
      assertTrue(envelope.positionMax() < limits.softMax(),
          archetype + " @ range " + range + ", margin " + marginFraction
        + ": supervisor band must trip STRICTLY before the device does");
      assertTrue(envelope.positionMin() > limits.softMin());
      assertTrue(envelope.positionMax() > envelope.positionMin(), "band must be non-empty");
    }
  }
}
```

### 7.2 Abort conditions — the complete list

Conditions 1–11 are checked **every loop**, in this order, by `TuningSupervisor.check()`. The first one that trips wins.

| # | Condition | `AbortReason` | Student-facing message |
|---|---|---|---|
| 1 | Enable trigger released (when `requireHeldEnable`) | `ENABLE_RELEASED` | "You let go of the trigger. Nothing is broken - hold it again and press Retry." |
| 2 | `MatchContext.isDisabled()` | `DISABLED` | "Robot disabled. The routine stopped where it was." |
| 3 | Position outside `[positionMin, positionMax]` | `LIMIT_REACHED` | "Stopped: the elevator reached 1.330 m and the safe band ends at 1.327 m." |
| 4 | Predicted position at current velocity + 150 ms outside the band | `LIMIT_APPROACH` | "Stopped early: at this speed you would hit the top in 0.15 s." |
| 5 | `abs(velocity) > maxAbsVelocity` | `OVERSPEED` | "Stopped: 6.2 rad/s is faster than this mechanism should ever go. Check your gear ratio." |
| 6 | Stator current > `maxStatorAmps` for `holdoffSeconds` | `OVERCURRENT` | "Stopped: 72 A for 0.15 s. Something is jammed or the mechanism is at a hard stop." |
| 7 | Commanded > `stallVoltsThreshold` but `abs(velocity) < stallVelocityThreshold` for `stallSeconds` | `STALLED` | "Stopped: 4 V applied for 0.5 s and nothing moved. Check the breaker, the CAN ID, and whether it is at a hard stop." |
| 8 | Velocity sign opposite to commanded voltage sign for > 0.3 s, above a deadband | `WRONG_DIRECTION` | "Stopped: positive voltage is making this move in the negative direction. Fix the invert before tuning." |
| 9 | Elapsed > `maxRoutineSeconds` | `TIMEOUT` | "Stopped: this step took longer than expected. Usually the mechanism is not reaching the speed we asked for." |
| 10 | Any measurement NaN or non-finite | `SENSOR_FAULT` | "Stopped: the encoder returned an invalid value. Check the sensor wiring." |
| 11 | Position unchanged for 0.5 s while velocity reads non-zero (or vice versa) | `SENSOR_INCONSISTENT` | "Stopped: position and velocity disagree. One of them is stale - check `optimizeBusUtilization` and your signal update rates." |
| **12** | **Not checked by `check()`.** Raised by `StepResponseStep` when `StepResponseAnalyzer` classifies a refinement iteration `UNSTABLE` (§9.3) | `UNSTABLE_RESPONSE` | "Stopping. The response was growing instead of settling, which is how mechanisms break. kP has been cut to 40% of what it was. Press Retry when you're ready." |

> **Twelve `AbortReason` values, eleven loop-checked conditions.** [`ROADMAP.md`](../ROADMAP.md) M7's gate says *"all 12 abort conditions"* and revisions 1 through 3 listed eleven here while quietly using a twelfth (`UNSTABLE_RESPONSE`) in §9.3 and smuggling two fit failures into the same enum. `AbortReason` now has exactly these twelve values; fit failures are `FitFailure` (§6.4); and `SafetyAbortTest` runs one fault-injection case per value, twelve in total.

> Condition 11 is a direct response to 8793's 2026 `ShooterSubsystem` as read in 2026-08, where `optimizeBusUtilization()` was called on three motors with **no preceding `setUpdateFrequency`** (that repository has since added one per motor). Any `getPosition()` on such a device returns a frozen value forever, with no error. A tuner that fits a model to a frozen signal produces confident garbage; this check catches it in half a second and names it.

Velocity-runaway (condition 5) deserves a note: for a `FLYWHEEL` the free-speed prior is the right ceiling, but for `DRIVE_VELOCITY` on blocks the wheels spin to free speed instantly and the check fires immediately. That is *correct behavior* — a drivetrain cannot be characterized on blocks — and the message says so: `"Stopped: the wheels reached free speed almost instantly. A drivetrain cannot be characterized on blocks; put it on the floor with at least 3 m of clear space."`

### 7.3 `TuningSupervisor`

```java
package org.rootstock.control;

import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * Single choke point for every volt a tuning routine commands. Nothing in
 * org.rootstock.tuning calls {@link TuningTarget#setVoltage} directly, and
 * {@code TuningSupervisorCallerTest} fails the build if anything does.
 */
public final class TuningSupervisor {

  public TuningSupervisor(TuningTarget target, SafetyEnvelope envelope, BooleanSupplier enableHeld) { /* ... */ }

  /**
   * Arm the supervisor. Throws {@link IllegalStateException} - not an alert, a hard throw - if any
   * precondition fails, because a routine that runs without these is the one that breaks a robot:
   * <ol>
   *   <li>soft limits are configured on the device (position archetypes only);</li>
   *   <li>{@code travelLimits().range()} is finite and positive, and
   *       {@link SafetyEnvelope#validate} returned no errors (position archetypes only);</li>
   *   <li>{@link TuningTarget#statorCurrentAmps()} is present, OR the caller passed
   *       {@code allowNoCurrentSensing()} explicitly;</li>
   *   <li>the mechanism is currently inside the safe band;</li>
   *   <li>the robot is enabled and not connected to an FMS;</li>
   *   <li>{@code RootstockLog.isReplay()} is false;</li>
   *   <li>{@code MatchContext.isDiagnostics()} is true — see section 7.4. In code
   *       preconditions 5 and 7 collapse to the single expression
   *       {@code MatchContext.isDiagnostics() && MatchContext.isEnabled()
   *              && !MatchContext.isFMSAttached()};</li>
   *   <li>{@link TuningTarget#isHomed()} is true;</li>
   *   <li>for position archetypes, {@link TuningTarget#positionReference()} is an
   *       {@code Absolute}/{@code FusedAbsolute} source, or a {@code HomedAgainstSwitch} whose
   *       homing completed since the last power cycle. {@code RotorOnly} and {@code AssumeAtBoot}
   *       are rejected;</li>
   *   <li>when both an absolute and a rotor-derived position exist, they agree within
   *       {@code 2 * effectiveToleranceSi()};</li>
   *   <li><b>(new in revision 4)</b> for {@code archetype().hasGravity()}, the device's idle mode is
   *       readable and is {@code BRAKE}, OR the wizard was constructed with
   *       {@code acknowledgeCoastRisk(String)} — see below and section 7.5.</li>
   * </ol>
   *
   * <p><b>Containment (revision 4).</b> {@code TuningWizard} is the <b>sole</b> caller of this
   * method. It wraps the call in a {@code try/catch (IllegalStateException)}, publishes
   * {@code getMessage()} verbatim to {@code /RootstockTuner/safety/message}, raises
   * {@code Alerts.warning("Tuning", msg, MatchImpact.PIT_ONLY)}, and remains in
   * {@link org.rootstock.tuning.wizard.WizardState#READY} with the arm refused. <b>The exception
   * never leaves {@code wizard.periodic()}</b>, and therefore never reaches
   * {@code robotPeriodic()} — which would kill the robot code loop and violate design/01's
   * "degrade, never crash" principle with the library's own throw. The hard throw is right for the
   * API contract (a mis-wired caller must not silently get an unarmed supervisor); the containment
   * boundary is what makes it safe, and {@code WizardTestModeTest} asserts non-propagation
   * explicitly.
   */
  public void arm() { /* ... */ }

  /** Clamp, apply, and record. Returns the volts actually applied. */
  public double commandVolts(double requestedVolts) { /* ... */ return 0; }

  /** Run every loop. Returns the abort reason if one tripped this cycle. */
  public Optional<AbortReason> check() { /* ... */ return Optional.empty(); }

  /**
   * Neutral the mechanism, publish the reason, latch until re-armed. Idempotent, never throws.
   *
   * <p><b>Order matters, and revision 4 fixes it:</b>
   * <ol>
   *   <li>{@code target.restoreNeutralMode()} — FIRST, before anything else. If a step borrowed
   *       COAST for a measurement (section 8.4), neutralling while still in coast is exactly the
   *       "letting go of a gravity mechanism" hazard this section warns about everywhere else.</li>
   *   <li>{@code target.stop()}.</li>
   *   <li>publish the reason and the sentence; latch.</li>
   * </ol>
   */
  public void abort(AbortReason reason) { /* ... */ }

  public boolean isArmed() { /* ... */ return false; }
  public Optional<AbortReason> lastAbort() { /* ... */ return Optional.empty(); }

  /**
   * The tolerance actually in use: {@code target.toleranceSi()} when finite, else
   * {@code 0.005 * travelLimits().range()} for a position archetype or
   * {@code 0.01 * plantPrior().freeSpeedSi()} for a velocity one. The substitution is narrated.
   */
  public double effectiveToleranceSi() { /* ... */ return 0; }
}
```

`commandVolts` performs, in order: clamp to `[-maxVolts, +maxVolts]`; apply a slew limit of `maxVolts / 0.05` V/s so no step can be instantaneous at the hardware; taper toward the **hold voltage** over the last 10% of the safe band on the approach side; then `target.setVoltage(...)`.

> **The taper target is the hold voltage, not zero.** On a `FLYWHEEL` or a `TURRET`, tapering to zero as you approach the band edge is a brake. On an `ARM` or an `ELEVATOR`, tapering to zero is *releasing the mechanism*, which accelerates it into the very limit the taper was trying to avoid. So for `archetype().hasGravity()` the taper floor is `kGbest * gravityShape(position)` using the best kG known this session (the `PlantPrior` estimate `kGprior` before the bisection has run, the bisected value after), and only the *excess* above that floor is tapered away. `SafetyAbortTest` includes a gravity case asserting that a `LIMIT_APPROACH` on an arm leaves the arm holding, not falling.

#### 7.3.1 Neutral-on-abort and gravity — the contradiction, and the decision

Revisions 1 through 3 contained a genuine internal contradiction that nobody in three revisions noticed, because the two halves are five hundred lines apart:

- §7.3's taper note says, correctly, that on a gravity mechanism *"tapering to zero is releasing the mechanism."*
- §8.4's `abortProbe` says, correctly, *"hold at kG_best, do not release."*
- And `abort(reason)` — which runs after **every one** of the eleven conditions in §7.2, including `ENABLE_RELEASED`, the most common one by a wide margin — commanded **neutral**. On a coast-mode arm at mid-travel, letting go of the trigger dropped the arm onto its hard stop from wherever it was.

The design applied the release-hazard insight to the taper and to the probe, and not to the abort path that runs after all of them.

**Decision: pre-flight requires BRAKE for gravity archetypes, and neutral-on-abort is then mechanically safe.** Of the two resolutions the review put to the maintainer, this is the simpler one, it makes the §8.4 drift-probe fix cleaner (the probe then *knows* brake is guaranteed and borrows coast deliberately rather than hoping), and it does not add a voltage-commanding path to the abort handler — which is the one place in the library where "do less" is the right instinct.

Concretely:

1. **`arm()` precondition 11** (above): for `archetype().hasGravity()`, `neutralMode()` must be present and `BRAKE`.
2. **If it is `COAST`**, the message is: `"Arm is set to coast. The tuner neutrals the mechanism whenever you let go of the trigger, and a coasting arm falls. Set NeutralMode.BRAKE in your ControlConfig, or call TuningWizard.acknowledgeCoastRisk(\"why\") if you know what you are doing."`
3. **If it is empty** (the adapter cannot read it back), the message is: `"I cannot read this mechanism's idle mode back, and a gravity mechanism that coasts falls when the tuner lets go. Implement TuningTarget.neutralMode(), or acknowledge the risk with TuningWizard.acknowledgeCoastRisk(\"why\")."`
4. **`acknowledgeCoastRisk(String)`** is the same deliberate friction as `skipSimPromotion` and `acknowledgeSharedController`: a free-text reason, logged verbatim into the tuning report, and a persistent warning alert for the rest of the session.
5. **`abort(reason)` restores the configured idle mode before it neutrals** (see the javadoc above), so the one window in which a gravity mechanism is legitimately in coast — the §8.4 sign probe — is not also a window in which an abort releases it.

`SafetyAbortTest` gains a gravity case for `ENABLE_RELEASED` mirroring the existing `LIMIT_APPROACH` one: on a `SingleJointedArmSim` at mid-travel with brake modeled, releasing the trigger must leave the arm within 2° of where it was after one second.

#### 7.3.2 The position-reference preconditions, and why they are hard throws

Preconditions 8, 9 and 10 close the widest hole in the original design. The failure they prevent, concretely:

A `PositionMechanism` declared with `PositionReference.AssumeAtBoot(Math.toRadians(95))` that was pushed by hand while the robot sat disabled reports `measuredSi() == 95°` while the arm is physically resting on its bottom stop at −10°. Then:

- `travelLimits().insideSoft(95°)` passes, so the old `arm()` succeeded.
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
| 11, coast | see §7.3.1 |

**The ARM zero-convention check re-runs at `arm()`, not only at pre-flight.** For `ARM` targets with an absolute source, `arm()` re-reads `absolutePositionSi()` and re-verifies that `horizontalReferenceSi()` is still consistent with it, using the same 5-degree threshold as the pre-flight step (§8.8 row 1). Pre-flight is a one-time check; arming happens before every single step, and it is the last moment before voltage.

**`isHomed()` defaults.** `TuningTarget.isHomed()` returns `true` by default only for `FLYWHEEL` and `DRIVE_VELOCITY`, which have no meaningful absolute position and no position aborts. Every position archetype must override it. This is a deliberate compile-time-visible burden on the mechanism layer: a mechanism that cannot answer "do I know where I am" is a mechanism the tuner will not move.

### 7.4 Held-enable is a physical trigger, on a dedicated controller, in Test mode only

**Decision: the wizard is driven from a gamepad; the dashboard is the display; motion is impossible outside Test mode; and the wizard never shares a controller with the driver.**

The student running a tuning routine is standing next to the robot with a controller in their hands. A dashboard "Run" button means walking to a laptop, and a latching toggle means nothing stops the mechanism when they let go. So the wizard is gamepad-driven — but the first version of this design shipped two mistakes that together made it the most dangerous component in the library, and both are fixed here.

#### 7.4.1 Motion is impossible outside Test mode — by construction

`TuningSupervisor.arm()` throws unless `MatchContext.isDiagnostics()` (precondition 7, §7.3). Not a convention, not a doc note, not "we suggest enabling in Test mode": a hard throw at the only place voltage can be authorized.

> **`MatchContext.isDiagnostics()`, not `DriverStation.isTest()`.** `design/06` §10 states that `isDiagnostics()` is the one spelling for Test/Utility mode across the library, and ArchUnit rule 10 makes `MatchContext` the only class permitted to read `DriverStation` at all. Revisions 1 through 3 of this document read `DriverStation` directly in four places, which is why domain 06's `onlyMatchReadsDriverStation` rule is currently red (`DESIGN.md` §16 item 3).

The failure this closes: `TuningWizard.periodic()` is called from `robotPeriodic()`, and the original design hard-returned only under FMS. On a practice field with no FMS attached, in teleop, a driver holding right trigger to shoot was simultaneously satisfying the wizard's held-enable. For a library whose stated safety case is "unsupervised 14-year-olds commanding raw voltage to arms," that was the one place it failed. `MatchContext.isDiagnostics()` is false during teleop and autonomous, so the wizard now cannot command a volt during a match, a practice match, or a demo, regardless of what any button is doing.

#### 7.4.2 The wizard gets its own controller port

The bindings below collide, button for button, with a typical driver map. `DESIGN.md` §10A's `ControlMap` binds the driver's right trigger to `SuperState.SHOOT`, A/B/Y to L2/L3/L4, X to Stow, Start to Home, and left stick to drive — every single wizard control. Sharing a controller between "drive the robot" and "authorize raw voltage to an arm" is not a binding conflict, it is a safety architecture error.

So the default is a **dedicated port**, and sharing must be stated out loud:

```java
// The default, and every example in this document:
private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));

// Sharing a controller with the driver — legal, logged verbatim, and deliberately awkward:
private final TuningWizard m_tuner =
    TuningWizard.using(m_driver).acknowledgeSharedController(
        "Only one controller at this event; wizard runs in Test mode only");
```

`TuningWizard.using(CommandXboxController)` checks at construction whether the controller's port is also registered with `ControlMap`. If it is, and `acknowledgeSharedController(String)` was not called, the wizard raises `Alerts.error("Tuning", ..., MatchImpact.PIT_ONLY)` naming the conflict and refuses to leave `IDLE`:

> `"The tuning wizard and the driver controls are both on controller port 0. Right trigger means 'shoot' to your driver and 'authorize motion' to the wizard. Move the wizard to its own port with TuningWizard.using(new CommandXboxController(2)), or call acknowledgeSharedController(\"why\") if you really only have one controller."`

The acknowledgment string is logged verbatim into the tuning report and shown as a persistent warning alert for the rest of the session, matching the `skipSimPromotion` and `acknowledgeCoastRisk` patterns — the same deliberate friction, for the same reason.

#### 7.4.3 The bindings

| Control | Binding | Behavior |
|---|---|---|
| **Enable** | Right trigger held past 0.5 | Required for any motion. Release = immediate neutral, `ENABLE_RELEASED`. |
| **Accept** | A | Commit the step's result and advance. |
| **Retry** | B | Re-run the current step from scratch. |
| **Back** | X | Return to the previous step; its gain reverts. |
| **Next / Skip** | Y | Skip the step, keeping the existing gain. Logs a warning in the report. |
| **Abort all** | Start | Neutral everything, revert **all** gains to the session's starting values, state = `ABORTED`. |
| **Predict** | D-pad up / left / right | Select one of the three outcomes in a `PredictStep` (§8.4). No motion. |
| **Manual nudge** | Left stick Y | Only in `REVIEW` state; moves the mechanism at up to 15% output so a student can reposition it by hand-ish. |

A secondary control path exists for laptop-only workflows: momentary boolean topics under `/RootstockTuner/cmd/` that the robot consumes and resets to `false` in the same loop. When a routine is driven this way, `requireHeldEnable` cannot be satisfied, so `maxVolts` is halved and `maxRoutineSeconds` is capped at 3 s. This is stated in the UI: `"No gamepad enable held - running in reduced-power mode."` Test mode is still required; the dashboard path relaxes the *held* enable, never the *mode*.

### 7.5 `MechanicalHealthCheck` — run before you touch a gain

Both ArchdukeTim and SamCarlberg make the same point in the community PID-tuning thread: PIDF control cannot compensate for slop and backlash, and proper tuning requires tensioned belts and shimmed gears *first*. Nothing in the FRC ecosystem checks this. Telling a student "your belt is loose, fix that before touching kP" is worth more than any gain the library could compute.

**Ownership (D23): this stays in tuning.** It commands raw voltage and must go through `TuningSupervisor`, so it cannot live in `design/06` where the passive checks are. `TuningHealth` — the passive "is this still tuned?" check (§10.5) — *is* registered as a `HealthSource` and is consumed by `design/06`. (§18 open question 11 is answered by this decision.)

`MechanicalHealthCheck.command(target)` runs in about 12 seconds and produces a `HealthReport`:

| Test | Method | Failure signature |
|---|---|---|
| **Idle mode read-back** | `target.neutralMode()`. Run **first**, because §7.3.1's precondition 11 and §8.4's sign probe both depend on the answer. | Absent, or `COAST` on a `hasGravity()` archetype → **BLOCK** with the §7.3.1 message |
| **Backlash / deadband width** | Ramp voltage from 0 to +2 V at 0.5 V/s, record position at breakaway; return to rest; ramp to -2 V, record. The position difference is the deadband. | `> 2 degrees` (rotational) or `> 2 mm` (linear) → `"About 4.1 degrees of slop. Tighten the chain or shim the gears - no PID gain can fix backlash."` |
| **Asymmetric friction** | Compare breakaway voltage in both directions. **On a gravity archetype this measurement is also the sign probe's fallback — see §8.4.** | ratio > 1.6 on a *non-gravity* archetype → `"It takes 0.9 V to move one way and 0.35 V the other. Something is dragging in one direction."` |
| **Encoder slip** | If an absolute encoder is present, compare integrated relative position against absolute after a full sweep. | `> 1%` of travel → `"The motor encoder and the absolute encoder disagree by 12 mm after one sweep. Belt or chain is skipping."` |
| **Sensor liveness** | Command 1.5 V for 0.4 s and require both position and velocity to change. | either frozen → `SENSOR_INCONSISTENT` |
| **Direction** | Positive voltage must produce positive position change. | inverted → **hard stop**, `"Positive voltage moves this mechanism in the negative direction. Fix the invert; do not tune around it."` |
| **Gravity present?** | Borrow COAST if the target allows it, neutral the mechanism at mid-travel for 1 s, measure drift, restore. If COAST cannot be borrowed, use the breakaway asymmetry instead. | drift (or asymmetry) above threshold on a `TURRET`/`FLYWHEEL` → `"This is configured as a turret (no gravity) but it fell 8 cm when released. Is it actually an arm or an elevator?"` |

`HealthReport.verdict()` is `PASS`, `WARN`, or `BLOCK`. The wizard refuses to proceed on `BLOCK`, and shows the warnings inline on `WARN`.

### 7.6 The sim-first promotion gate — it tests the ENVELOPE, not the fit

Before the wizard will arm hardware for a mechanism, the recipe must complete in simulation against a **Monte-Carlo perturbed plant**, and the promotion is granted on a **safety** criterion, not a quality one.

**What was wrong with the obvious version.** The first form of this gate simply required the recipe to complete in simulation, keyed on `configHash = hash(PlantPrior + TravelLimits + archetype + siDomain)`. That gate cannot fail. In simulation the plant *is* `PlantPrior` — the wizard identifies the exact model it was handed, every fit is near-perfect, every step response classifies `GOOD`, and the gate passes unconditionally. It cannot catch a wrong gear ratio, backlash, a slipping belt, an inverted encoder, or a wrong arm zero, which are the actual causes of tuning accidents. It was pure friction with an escape hatch (`skipSimPromotion`) that students would find in week one, and `DESIGN.md` §14 row 26 sold it as the primary safety property. That claim was not true.

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

- `configHash` is a stable hash of `PlantPrior` + `TravelLimits` + `archetype` + `siDomain`. Change the gearing, and the promotion is void.
- Override: `TuningWizard.skipSimPromotion("I have read the safety notes")` — a literal string argument, logged verbatim into the tuning report and shown as a persistent warning alert for the rest of the session. Deliberately annoying.
- The nine runs execute headless and unattended in about 40 s of sim time; the student is not asked to watch them. The full *teaching* recipe in simulation (§13.11) is a separate, interactive thing.
- `SimPromotionGateTest` (§16.2) injects a plant that the supervisor genuinely cannot contain — a 6x kA with a 20-degree safe band — and asserts the gate **refuses**. A gate with no failing test case is a gate nobody has checked.

**Demotion, stated plainly.** This gate is no longer the headline safety property of the tuning domain, and `DESIGN.md` §14 row 26 must be updated to say so. The two properties that actually carry the safety case are:

1. **The `MechanicalHealthCheck` `BLOCK` verdict** (§7.5) — it refuses to tune an inverted, slipping, frozen-sensor, or coast-mode-gravity mechanism, which is the single most destructive class of tuning accident.
2. **The homing / position-reference preconditions on `arm()`** (§7.3.2) — they refuse to command voltage against a position the robot only *assumes*.

The sim gate is the third, and its value is real but bounded: it proves the envelope holds under plant error. That is a genuine property and it should be advertised as exactly that much.

---

## 8. The guided tuning wizard

### 8.1 Architecture

The wizard is a state machine over a **recipe**, which is an ordered list of **steps**. Recipes are data; steps are small strategy objects. Nothing about the wizard is mechanism-specific — the six built-in recipes are just six lists.

```java
package org.rootstock.tuning.wizard;

import org.rootstock.control.GainId;

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

  /**
   * Called every loop while RUNNING. <b>Every implementation's first statement is
   * {@code if (ctx.supervisor().check().isPresent()) { ...; return; }}</b> — the abort list is only
   * live if something calls it, and this is the only place that does.
   */
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
package org.rootstock.tuning.wizard;

/** Everything a step is allowed to touch. */
public interface StepContext {
  org.rootstock.control.TuningTarget target();
  org.rootstock.control.TuningSupervisor supervisor();    // the ONLY way to command volts
  org.rootstock.control.Gains gains();                    // gains as accumulated so far this session
  double toleranceSi();                                   // supervisor.effectiveToleranceSi()
  double elapsedSeconds();                                // Clock.seconds() based, never Timer
  double dt();                                            // Clock.dt()
  org.rootstock.tuning.sysid.SampleBuffer buffer();       // decimated ring buffer for plots + analysis
  void narrate(String line);                              // appends to /RootstockTuner/log
  void publishProgress(double fraction0to1);
}
```

```java
package org.rootstock.tuning.wizard;

import org.rootstock.control.GainId;
import org.rootstock.control.Gains;

/** The outcome of a step, presented to the student for accept / retry / skip. */
public record StepResult(
    Outcome outcome,
    java.util.Optional<GainId> gain,
    double value,                 // the suggested new value
    double previousValue,
    String headline,              // "kS = 0.220 V"
    String quality,               // "Fit R2 = 0.981, RMSE 0.09 V - good"
    String verdict,               // plain-language diagnosis
    String recommendation,        // plain-language next action

    /**
     * Whether the student's prediction for this step was right. Empty when the step had no
     * {@link org.rootstock.tuning.wizard.steps.PredictStep} in front of it, or when the student
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
| `READY` | Mechanism selected; recipe loaded; step 1 shown with its explanation. **An `arm()` that threw lands back here**, with the message published (§7.3). | no |
| `PREFLIGHT` | `MechanicalHealthCheck` running (once per session, before step 1). | yes, low power |
| `ARMED` | Step explained, `willDo()` shown, supervisor armed, waiting for the trigger. | no |
| `RUNNING` | Step executing. | yes |
| `REVIEW` | Step complete; `StepResult` shown with plots. Student presses A/B/Y. | manual nudge only |
| `ABORTED` | Something tripped. Reason shown. Gains reverted to the step's starting values. | no |
| `DONE` | Recipe complete; report generated; gains staged for persistence. | no |
| `DISABLED_REPLAY` | `RootstockLog.isReplay()` is true. | no |

Invariants enforced in code:
- Motion is possible in exactly two states, and both require `supervisor.isArmed()`.
- Every transition out of `RUNNING` calls `supervisor.abort(...)` — which restores the idle mode before it neutrals (§7.3) — before anything else.
- Entering `ABORTED` restores `gains` to the snapshot taken on entry to `ARMED`, and pushes them through `gainSink()`.
- `Start` (abort all) restores the snapshot taken when the *session* began, not the step.

### 8.3 `TuningWizard` — the public surface

```java
package org.rootstock.tuning.wizard;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;

public final class TuningWizard {

  /**
   * Build the wizard over every registered {@link org.rootstock.control.TuningTarget}.
   * The controller supplies enable/accept/retry/back/skip/abort/predict per section 7.4.
   *
   * <p><b>Give the wizard its own port.</b> At construction this checks
   * {@code ControlMap.isPortRegistered(controller.getHID().getPort())}; if the port is also a
   * driver or operator port and {@link #acknowledgeSharedController(String)} has not been called,
   * the wizard raises {@code Alerts.error("Tuning", ..., MatchImpact.PIT_ONLY)} naming the conflict
   * and refuses to leave {@link WizardState#IDLE}. See section 7.4.2 for the exact message.
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

  /**
   * Permit a gravity mechanism whose idle mode is COAST, or unreadable, to be armed anyway
   * (§7.3.1 precondition 11). Same friction, same logging, same persistent alert.
   */
  public TuningWizard acknowledgeCoastRisk(String reason) { /* ... */ return this; }

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
   * Call every loop. Costs one branch when the wizard is {@code IDLE}.
   *
   * <p><b>This is safe to call from {@code robotPeriodic()} because of
   * {@link org.rootstock.control.TuningSupervisor#arm()} precondition 7, not because of anything
   * this method does.</b> {@code arm()} throws unless {@code MatchContext.isDiagnostics()}, so
   * during teleop and autonomous the wizard can advance its own state machine and publish narration
   * but <i>cannot command a volt</i> — no button on any controller, held or not, can cause motion
   * outside Test mode. That is the property that makes the driver-controller collision described
   * in section 7.4.1 a UI annoyance instead of a safety failure.
   *
   * <p><b>This method is the containment boundary for {@code arm()}'s hard throw.</b> It catches
   * {@link IllegalStateException} from the single call site, publishes the message, and stays in
   * {@link WizardState#READY}. Nothing propagates out of here into {@code robotPeriodic()}.
   *
   * <p>It additionally hard-returns when {@code MatchContext.isFMSAttached()}, and publishes
   * {@code state = "DISABLED_REPLAY"} and returns when {@code RootstockLog.isReplay()}.
   */
  public void periodic() { /* ... */ }

  /** Command-based convenience: run the wizard for the whole time this command is scheduled. */
  public Command command() { /* ... */ return null; }

  public WizardState state() { /* ... */ return WizardState.IDLE; }

  /** Markdown report of everything done this session. See section 11.5c. */
  public String report() { /* ... */ return ""; }
}
```

> **Lifecycle note (D26, `DESIGN.md` §6).** A team that adopts `RootstockLifecycle` or `RootstockRobot` does **not** call `TuningWizard.periodic()` by hand: `RootstockRegistry.addAll(m_tuner, ...)` routes it into the priority-30 `LifecycleHook` alongside `TuningRegistry.drainPoller()`. §14.2 shows that shape. The bare `periodic()` remains public for the standalone, no-lifecycle path, which is a documented row in the §11c adoption matrix rather than an undocumented divergence — see §14.2.

### 8.4 Step primitives

Seven reusable step implementations cover every recipe.

#### `PredictStep` — the step that makes this teaching instead of narration

Without this step the wizard is a progress bar with good prose. A student can complete every recipe by pressing A eleven times and learn nothing, and **neither the wizard nor the mentor can tell the difference.** §13.11 tells the student to "tune them until you can predict what the plot is going to do before it does it" — but nothing in `TuningStep`, `StepContext`, `StepResult` or the NT schema ever asked for a prediction or scored one. That was the largest gap in the design as a teaching artifact.

`PredictStep` is interleaved before the `RUNNING` phase of the kS, kG, kP and verify steps. It commands no motion. It asks one multiple-choice question in plain language, records the answer, and — after the real step runs — tells the student whether they were right and *why*.

```java
package org.rootstock.tuning.wizard.steps;

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
/RootstockTuner/predict/question   string     "Your elevator's kD is 4.93 and its kV is 5.00. What if kD were zero?"
/RootstockTuner/predict/options    string[]   three plain-language outcomes
/RootstockTuner/predict/answer     double     RW - index 0/1/2, written by the ComboBox or the D-pad
/RootstockTuner/predict/score      string     "Predictions: 7 of 9"
```

**A real question, from this document's elevator, at the refinement step:**

> "Your elevator's kD is 4.93 V/(m/s) and its kV is 5.00 V/(m/s). I'm about to set kD to zero. What do you think the carriage will do?"
>
> 1. It will overshoot and bounce a few times before settling.
> 2. It will get there slowly and stop a little short.
> 3. It will get there and stop cleanly, just faster.

The correct answer is computed from the model — **this is not a hand-authored answer key.** `zeta = (kD + kV) / (2 * sqrt(kP * kA))` at the proposed gains; below 0.7 the answer is (1), above 1.2 it is (2), between them it is (3). For this mechanism at `kP = 128.00`, `kA = 0.060`, `kD = 0`, `kV = 5.00`:

```
zeta = (0 + 5.00) / (2 * sqrt(128.00 * 0.060)) = 5.00 / (2 * 2.771281) = 5.00 / 5.542563 = 0.9021
0.7 <= 0.9021 <= 1.2   ->   the answer key is option (3)
```

The step is *never* wrong about its own arithmetic, and the arithmetic is the same arithmetic §9.2.2 shows the student.

**`StepResult` carries the score.** The field is `java.util.Optional<Boolean> predictionCorrect`, declared in the canonical record in §8.1 — there is one definition of `StepResult` in this document and that is it. It is empty when the step had no `PredictStep` in front of it and when the student let the question time out.

**The `Coach` text branches on right-vs-wrong**, which is the whole point — a correct prediction is the moment to name the concept, and an incorrect one is the moment to connect the lesson to what they just watched:

> *(predicted clean, and it was clean)* "You said it would stop cleanly, and it did — 1.4% overshoot, no ringing. That is your mechanism's own kV doing the damping: 5.00 V per meter per second of back-EMF is worth more here than any kD we could add. That is exactly why the flywheel recipe leaves kD at zero."

> *(predicted overshoot, and it was clean)* "You said it would overshoot; it did not. Here is the tell, and it is the one most people miss: kV is *in the numerator* of the damping ratio. Your mechanism fights its own motion before kD does anything at all. With kD at zero, zeta is still 0.90 — comfortably above the 0.7 where bouncing starts. Watch the volts plot: the feedback line barely moves."

**The markdown report** (§11.5c) gains a `Predictions: 7/9` line and lists the missed ones with their questions. That single line is what makes a mentor able to tell, without watching, whether a student ran the wizard or *learned* from it.

**Cost: about 0.3 person-weeks.** It is one step primitive, four questions per recipe, one `Optional<Boolean>`, three NT topics and a report line. It is the difference between teaching and a progress bar, and it is the cheapest high-value item in this document.

#### `PreflightStep`
Runs `MechanicalHealthCheck` (§7.5). Produces no gain. On `BLOCK`, the recipe cannot proceed.

#### `BreakawayRampStep` — finds kS (and, on a gravity archetype, the sign of kG)
Ramps voltage from zero at a slow, fixed rate until motion is detected, in **both** directions, and averages.

```
rate      = 0.30 V/s                             (slow enough that inertia contributes nothing)
vMoveThreshold = max(0.02 * vFreePrior, 3 * velocityNoiseStdDev)
                                                  // noise std dev measured over 0.5 s at rest

for direction in {+1, -1}:
    u = 0
    while |velocity| < vMoveThreshold:            // driven from periodic(), see the note below
        u += direction * rate * dt
        supervisor.commandVolts(u + gravityCompensation())   // see note
    uBreak[direction] = |u|                       // RAW breakaway magnitude, before subtracting kG
    kS[direction] = |u| - |gravityCompensation()|
    stop(); settle(0.5 s); return to start position

kS = (kS[+1] + kS[-1]) / 2
asymmetry = max(kS[+], kS[-]) / min(kS[+], kS[-])
```

`gravityCompensation()` is `kG` for `GravityMode.CONSTANT`, `kG * cos(measuredSi() - horizontalReferenceSi())` for `COSINE`, and zero otherwise — which is why gravity mechanisms run a kG pre-pass first (§8.5). Without it, you are measuring "volts to lift the elevator", not "volts to overcome friction", and kS comes out ten times too large.

Reported: `kS = 0.220 V (0.226 up / 0.214 down, 6% asymmetry - fine)`. Asymmetry above 1.6 produces a warning and points at the health check.

> **Like every step in this document, this loop is a sub-state machine driven from `periodic()`, not a `while` with a `yieldOneLoop()`.** The pseudocode above is written in the readable form; the *implementation* is the shape §8.4's `HoldBisectionStep` shows in full, for the same reason and with the same `supervisor.check()`-first rule. `TuningStep` is a `begin`/`periodic`/`isComplete` interface, §5.6 rule 3 bans threads, and replay rule R5 bans anything that would suspend the main loop.

**The raw breakaway magnitudes are also the gravity-sign fallback.** On a gravity archetype with no gravity compensation applied yet, `|u_toward_gravity|` is `|kS − |kG||` and `|u_against_gravity|` is `kS + |kG|`. The direction that needs *more* voltage to break loose is the direction gravity opposes, which is the direction kG must push:

```
gSign  = signum(uBreak[+1] - uBreak[-1])                       // robust: the difference is 2*min(kS,|kG|)
seed   = 0.5 * |uBreak[+1] - uBreak[-1]|                       // == min(kS, |kG|); a LOWER BOUND on |kG|
```

That is an exact identity, not an approximation: `(kS + |kG|) − |kS − |kG|| = 2·min(kS, |kG|)`. It gives the **sign** reliably for any `|kG| > 0`, and a *lower bound* on the magnitude — which is all §8.4 needs, because the bisection finds the value. It is used only when the drift probe cannot see drift, and it works in brake mode, because a brake affects the *neutral* state and not a commanded voltage.

#### `HoldBisectionStep` — finds kG to three decimal places, without slamming a hard stop

This is the step that most clearly demonstrates why on-robot beats redeploy-and-guess. WPILib's own vertical-arm tutorial says you must zero in on kG *"fairly precisely, at least four decimal places"*. Twelve deploys will not get you there; five seconds of bisection will.

It is also, by a wide margin, the most dangerous step in the library, because it is **open-loop voltage on a gravity mechanism**. The first version of this step would have destroyed a real arm on iteration 1, and it is worth writing down exactly how, because the fix is shaped by the failure.

> **The failure, walked through on this document's own arm** (kV = 0.85 V/(rad/s), 120° travel, true kG ≈ 1.2 V). Iteration 1 sets `mid = 0.5 * (0 + 0.6 * 12) = 3.6 V` and commands `3.6 * cos(theta)` open-loop. That is 2.4 V of net excess over what gravity needs. Steady velocity ≈ 2.4 / 0.85 ≈ 2.8 rad/s ≈ 160 deg/s. The step then runs `settle(0.15 s)` followed by `mean(velocity) over the next 0.35 s` — half a second at 160 deg/s, so the arm travels **70–80 degrees inside a single measurement window**, on a mechanism with 120 degrees of travel. The position guard (`|position - startPosition| > 0.08 * travelRange`, i.e. 9.6°) was evaluated only *after* that window had already elapsed, so it could not intervene. `LIMIT_APPROACH`'s 150 ms horizon buys 24° of lookahead at that speed. And `commandVolts`'s "taper linearly to zero over the last 10% of the safe band" means, on a gravity arm, *releasing it*. `recentre()` was referenced and never defined, and there was no closed loop available to execute it.

Five changes, all required, all present below. Three are from revision 2; two are new in revision 4.

**(1) Bracket from physics, not from the supply voltage.** `0.6 * nominalVolts` is a bracket over "any voltage this motor can produce." The bracket we actually want is "any voltage plausibly needed to hold *this* load," which `PlantPrior` already tells us (§6.5, `kGprior` — **now with the factor-of-n bug fixed**, which is what makes the bracket contain the true kG on a multi-motor mechanism at all):

```
kGprior = tau_gravity / (G * motor.KtNMPerAmp / motor.rOhms)     // always a positive magnitude
lo      = gSign * 0.2 * kGprior
hi      = gSign * Math.min(0.6 * nominalVolts, 1.8 * kGprior)
```

On this document's elevator, `kGprior = 0.253323 V`, so the bracket is `[0.050665, 0.455981]` V and iteration 1 commands `mid ≈ 0.2533 V` — net excess near zero rather than 2.4 V. The `min` against `0.6 * nominalVolts` keeps the old ceiling as a backstop for a wildly wrong prior. A prior wrong by more than 1.8× is caught by the bracket-exhaustion branch below and reported, not silently ignored. **With revision 3's `÷ motorCount`, `kGprior` on this two-motor elevator was 0.126661 V, `hi` was 0.2280 V, and the true 0.2528 V sat outside the bracket entirely — ten wasted iterations followed by `RETRY_SUGGESTED` blaming a `PlantPrior` that was correct.**

**(2) The probe is a bounded pulse with an IN-window guard, and an abort is itself a measurement.** The guard moved inside the loop, the threshold tightened from 8% to 3% of travel, and — the important part — hitting the guard does not waste the iteration. *Which way it moved is the answer the iteration was asking for.*

**(3) `recentre()` is a real closed-loop move**, with a bounded time budget. The wizard holds a provisional feedback controller for the whole bisection, built from the same `PlantPrior` that produced `kGprior`.

**(4) *(new in revision 4)* The sign probe reads back the idle mode, borrows coast under supervision, and never takes the `kG = 0` shortcut on a gravity archetype.**

**(5) *(new in revision 4)* The whole step is an explicit sub-state machine**, because it has to be.

##### (5) first, because it changes the shape of everything else

Revisions 1 through 3 wrote `probe()` and `recentre()` as blocking `while` loops with a `yieldOneLoop()` call inside. **That cannot be implemented.** `TuningStep` is a `begin`/`periodic`/`isComplete` interface (§8.1) driven from the 20 ms main loop; §5.6 rule 3 bans background threads outright; and replay rule R5 bans anything that would suspend the loop, because a replay must reproduce the same call sequence. There is no `yieldOneLoop()` to write. Worse, neither loop called `supervisor.check()`, so the eleven abort conditions in §7.2 were *inert for the duration of the most dangerous step in the library*, and `recentre()` had no iteration bound or timeout at all — a mechanism whose stiction holds it just outside `toleranceSi` (entirely likely, since the provisional kP comes from a prior that may be 3× wrong) loops forever applying voltage.

`HoldBisectionStep` is therefore an explicit sub-state machine whose `periodic()` calls `supervisor.check()` as its **first statement**, and whose recentre phase is bounded twice — by a widened settle band and by a wall-clock budget.

```java
package org.rootstock.tuning.wizard.steps;

import edu.wpi.first.math.trajectory.TrapezoidProfile;
import org.rootstock.control.GravityMode;
import org.rootstock.tuning.FeedbackDesigner;

final class HoldBisectionStep implements TuningStep {

  // ---- sub-states -----------------------------------------------------------------------
  private enum Phase {
    SIGN_DRIFT,      // idle mode borrowed to COAST; watch which way it falls
    SIGN_BREAKAWAY,  // brake mode or no drift: measure the breakaway asymmetry instead
    PROBE,           // command mid * gravityShape(), watch position every loop
    RECENTRE,        // closed-loop walk back to startPos, time-budgeted
    DONE,
    FAILED
  }

  private static final int    ITERATIONS      = 10;
  private static final double PROBE_SECONDS   = 0.35;
  private static final double SETTLE_SECONDS  = 0.10;
  private static final double DRIFT_SECONDS   = 0.50;   // coast borrow window
  private static final double DRIFT_SECONDS_BRAKE_RETRY = 1.20;  // lengthened, per revision 4

  private Phase  m_phase = Phase.SIGN_DRIFT;
  private int    m_iteration;
  private double m_lo, m_hi, m_mid, m_gSign;
  private double m_startPos, m_range;
  private double m_phaseElapsed;
  private double m_sumVel; private int m_velSamples;
  private boolean m_coastBorrowed;

  private TrapezoidProfile m_profile;
  private TrapezoidProfile.State m_state, m_goal;
  private double m_kP, m_recentreBudget, m_settleBand;

  /**
   * The best kG known right now. Seeded from {@code gSign * kGprior} BEFORE the first probe — not
   * zero — because {@code recentre} and the supervisor's gravity-aware taper (§7.3) both use it to
   * HOLD the mechanism, and a zero here is a release. Updated to the bracket midpoint after every
   * iteration.
   */
  private double m_kGbest;

  /**
   * The result of one bounded probe. An aborted probe is NOT a wasted iteration: the direction the
   * mechanism moved before the guard tripped is exactly the comparison the bisection was about to
   * make, so the bracket updates either way. This is why tightening the guard from 8% to 3% of
   * travel costs nothing in convergence.
   */
  private sealed interface ProbeOutcome {
    record Measured(double drift) implements ProbeOutcome {}
    record AbortedUp()            implements ProbeOutcome {}
    record AbortedDown()          implements ProbeOutcome {}
  }

  // ---- entry ----------------------------------------------------------------------------

  @Override public void begin(StepContext ctx) {
    var prior      = ctx.target().plantPrior();
    m_startPos     = ctx.target().measuredSi();
    m_range        = ctx.target().travelLimits().range();
    m_settleBand   = Math.max(ctx.toleranceSi(), 0.005 * m_range);

    var pref = FeedbackDesigner.Preferences.defaultsFor(
        ctx.target().archetype(), ctx.target().travelLimits(), ctx.toleranceSi());
    m_kP = FeedbackDesigner.forPosition(prior.kVprior(), prior.kAprior(), pref).kP();

    // A deliberately gentle profile: quarter speed, quarter acceleration.
    m_profile = new TrapezoidProfile(new TrapezoidProfile.Constraints(
        0.25 * prior.freeSpeedSi(), 0.25 * prior.maxAccelSi()));
    // 3x the gentle profile's own duration for the worst move the guard permits, plus half a second.
    m_recentreBudget = 3.0 * profileDurationFor(0.03 * m_range) + 0.5;

    m_kGbest = 0.0;                     // no sign yet; the sign phases set it before any hold
    m_phase  = ctx.target().overrideNeutralMode(NeutralMode.COAST)
                 ? beginCoastBorrow(ctx)
                 : Phase.SIGN_BREAKAWAY;
  }

  // ---- the one loop ----------------------------------------------------------------------

  @Override public void periodic(StepContext ctx) {
    // FIRST STATEMENT, every phase, no exceptions. The eleven abort conditions of section 7.2 are
    // only live because this line runs; revisions 1-3's while-loops never called it.
    if (ctx.supervisor().check().isPresent()) { failTo(ctx, Phase.FAILED); return; }

    m_phaseElapsed += ctx.dt();
    switch (m_phase) {
      case SIGN_DRIFT     -> signDrift(ctx);
      case SIGN_BREAKAWAY -> signBreakaway(ctx);
      case PROBE          -> probe(ctx);
      case RECENTRE       -> recentre(ctx);
      case DONE, FAILED   -> { /* nothing; isComplete() is true */ }
    }
  }

  @Override public boolean isComplete(StepContext ctx) {
    return m_phase == Phase.DONE || m_phase == Phase.FAILED;
  }

  // ---- step 0a: which way does this thing fall? (coast borrowed) --------------------------
  //
  // Determines the SIGN of kG by measurement instead of assuming "positive position = up."
  // A hood or wrist whose positive direction is downward has a genuinely negative kG; it passes
  // the MechanicalHealthCheck direction test and the old [0, 0.6*V] bracket could not represent it.

  private Phase beginCoastBorrow(StepContext ctx) {
    m_coastBorrowed = true;
    ctx.narrate("Switching to coast and letting go for half a second, to see which way this falls. "
              + "Brake mode goes back on the moment I have the answer.");
    ctx.target().stop();
    m_phaseElapsed = 0; m_sumVel = 0; m_velSamples = 0;
    return Phase.SIGN_DRIFT;
  }

  private void signDrift(StepContext ctx) {
    m_sumVel += ctx.target().velocitySi(); m_velSamples++;
    double window = m_coastBorrowed ? DRIFT_SECONDS : DRIFT_SECONDS_BRAKE_RETRY;
    if (m_phaseElapsed < window) return;

    double meanVel = m_sumVel / Math.max(m_velSamples, 1);
    releaseCoastBorrow(ctx);                                  // brake back on BEFORE anything else

    if (Math.abs(meanVel) >= driftDeadband(ctx)) {
      m_gSign = Math.signum(-meanVel);   // falls negative -> kG must push positive -> gSign = +1
      beginBisection(ctx);
      return;
    }
    // No visible drift even in coast. High-reduction gearboxes hold themselves; stiction at the
    // rotor can exceed back-driven gravity torque. Fall through to the breakaway measurement.
    ctx.narrate("It did not move when I let go — your gearbox is holding it against gravity. "
              + "I will find which way gravity pulls from how hard it is to break loose in each "
              + "direction instead.");
    m_phase = Phase.SIGN_BREAKAWAY; m_phaseElapsed = 0;
  }

  private void releaseCoastBorrow(StepContext ctx) {
    if (m_coastBorrowed) { ctx.target().restoreNeutralMode(); m_coastBorrowed = false; }
  }

  // ---- step 0b: the brake-mode / stiction fallback -----------------------------------------
  //
  // Runs BreakawayRampStep's raw magnitudes (section 8.4). Works with the brake engaged, because a
  // brake changes the NEUTRAL state and not a commanded voltage.

  private void signBreakaway(StepContext ctx) {
    var r = m_breakaway.step(ctx);                      // the same sub-machine BreakawayRampStep uses
    if (!r.done()) return;

    double diff = r.uBreakPositive() - r.uBreakNegative();
    if (Math.abs(diff) < 3.0 * r.breakawayNoiseStdDev()) {
      // TURRET / FLYWHEEL / STEER: gravity really is absent, and the pre-flight gravity test
      // already agreed. This is the ONLY archetype set for which kG = 0 is a legitimate answer.
      if (!ctx.target().archetype().hasGravity()) {
        ctx.narrate("This mechanism does not move when released and breaks loose equally hard in "
                  + "both directions, so there is nothing for kG to hold. Setting kG = 0.");
        m_kGbest = 0.0; m_phase = Phase.DONE; return;
      }
      // ELEVATOR / ARM: we do NOT set kG = 0. Doing so makes BreakawayRampStep measure gravity as
      // friction (kS comes out about ten times too large -- section 8.5's own warned failure) and
      // leaves the joint OLS fit with a gravity-shaped residual.
      ctx.narrate("I could not tell which way gravity pulls: this mechanism does not drift when "
                + "released and breaks loose equally hard in both directions. That usually means a "
                + "very high reduction or a lot of stiction. I am NOT setting kG to zero -- an "
                + "elevator or an arm always has a kG. Check that the archetype is right, or run "
                + "the mechanical health check.");
      failTo(ctx, Phase.FAILED);                        // -> RETRY_SUGGESTED, kG untouched
      return;
    }
    m_gSign = Math.signum(diff);
    ctx.narrate(String.format(
        "It takes %.3f V to break loose one way and %.3f V the other. The harder direction is the "
      + "one gravity is fighting, so that is the way kG has to push.",
        Math.abs(r.uBreakPositive()), Math.abs(r.uBreakNegative())));
    beginBisection(ctx);
  }

  // ---- the bisection ------------------------------------------------------------------------

  private void beginBisection(StepContext ctx) {
    double kGprior = ctx.target().plantPrior().gravityVoltsPrior();     // section 6.5, n-corrected
    m_lo = m_gSign * 0.2 * kGprior;
    m_hi = m_gSign * Math.min(0.6 * ctx.target().plantPrior().nominalVolts(), 1.8 * kGprior);
    m_kGbest = m_gSign * kGprior;      // hold at the prior from the very first loop, never at zero
    m_iteration = 0;
    beginProbe(ctx);
  }

  private void beginProbe(StepContext ctx) {
    m_mid = 0.5 * (m_lo + m_hi);
    m_phase = Phase.PROBE; m_phaseElapsed = 0; m_sumVel = 0; m_velSamples = 0;
  }

  /**
   * One bounded probe, one loop's worth. Commands mid * gravityShape() and watches position
   * CONTINUOUSLY. Ends as soon as the mechanism has moved 3% of travel, which is well inside the
   * supervisor's own band and far inside any hard stop.
   */
  private void probe(StepContext ctx) {
    double delta = ctx.target().measuredSi() - m_startPos;
    if (Math.abs(delta) > 0.03 * m_range) {
      holdAtBest(ctx);                                        // hold at kGbest -- never release
      narrowBracket(delta > 0 ? new ProbeOutcome.AbortedUp() : new ProbeOutcome.AbortedDown(), 0.0);
      beginRecentre(ctx);
      return;
    }
    ctx.supervisor().commandVolts(m_mid * gravityShape(ctx));  // 1.0 for CONSTANT, cos(...) for COSINE
    if (m_phaseElapsed > SETTLE_SECONDS) { m_sumVel += ctx.target().velocitySi(); m_velSamples++; }
    if (m_phaseElapsed < PROBE_SECONDS) return;

    double drift = m_sumVel / Math.max(m_velSamples, 1);
    if (Math.abs(drift) < driftDeadband(ctx)) { m_kGbest = m_mid; m_phase = Phase.DONE; return; }
    narrowBracket(new ProbeOutcome.Measured(drift), drift);
    beginRecentre(ctx);
  }

  /**
   * Java 17: instanceof patterns only. No pattern-matching switch (preview in 17), no preview
   * features anywhere in Rootstock -- see section 17.
   *
   * Guard tripped: the DIRECTION of the abort IS the measurement. "Moved up" means we over-pushed
   * against gravity, so mid is too many volts.
   */
  private void narrowBracket(ProbeOutcome out, double drift) {
    if (out instanceof ProbeOutcome.AbortedUp) {
      if (m_gSign > 0) m_hi = m_mid; else m_lo = m_mid;
    } else if (out instanceof ProbeOutcome.AbortedDown) {
      if (m_gSign > 0) m_lo = m_mid; else m_hi = m_mid;
    } else if (out instanceof ProbeOutcome.Measured) {
      if (m_gSign * drift < 0) m_lo = m_mid; else m_hi = m_mid;
    }
    m_kGbest = 0.5 * (m_lo + m_hi);                           // hold at the new best, not the old one
  }

  private void beginRecentre(StepContext ctx) {
    m_state = new TrapezoidProfile.State(ctx.target().measuredSi(), ctx.target().velocitySi());
    m_goal  = new TrapezoidProfile.State(m_startPos, 0);
    m_phase = Phase.RECENTRE; m_phaseElapsed = 0;
  }

  /**
   * Walk the mechanism back to where the bisection started, under CLOSED LOOP, one loop at a time.
   *
   * <p>This is why the wizard holds a provisional feedback controller for the whole bisection:
   * open-loop recentring on a gravity mechanism is the same hazard the bisection itself is, and
   * "taper to zero" is a release. kP comes from the same physics prior that produced kGprior.
   *
   * <p><b>Bounded twice.</b> The settle band is {@code max(toleranceSi, 0.005 * range)} rather than
   * {@code toleranceSi} alone, and there is a wall-clock budget. Revisions 1-3 had neither, so a
   * mechanism whose stiction held it just outside tolerance -- likely, because the provisional kP
   * comes from a prior that may be 3x wrong -- applied voltage forever.
   */
  private void recentre(StepContext ctx) {
    m_state = m_profile.calculate(ctx.dt(), m_state, m_goal);
    double ff = m_kGbest * gravityShape(ctx);                 // hold, always
    double fb = m_kP * (m_state.position - ctx.target().measuredSi());
    ctx.supervisor().commandVolts(ff + fb);

    if (Math.abs(ctx.target().measuredSi() - m_startPos) <= m_settleBand) {
      if (++m_iteration >= ITERATIONS) { m_kGbest = 0.5 * (m_lo + m_hi); m_phase = Phase.DONE; }
      else beginProbe(ctx);
      return;
    }
    if (m_phaseElapsed > m_recentreBudget) {
      ctx.narrate(String.format(
          "I could not walk this mechanism back to where the probe started within %.1f s. Something "
        + "is holding it -- usually stiction larger than kP times your tolerance. Holding at "
        + "kG = %.4f V and stopping here.", m_recentreBudget, m_kGbest));
      failTo(ctx, Phase.FAILED);                              // -> RETRY_SUGGESTED, kGbest kept
    }
  }

  // ---- exits -------------------------------------------------------------------------------

  private void holdAtBest(StepContext ctx) {
    ctx.supervisor().commandVolts(m_kGbest * gravityShape(ctx));   // hold at kG_best, do not release
  }

  private void failTo(StepContext ctx, Phase p) {
    releaseCoastBorrow(ctx);          // brake back on before ANY neutral -- section 7.3
    m_phase = p;
  }

  private double gravityShape(StepContext ctx) {
    return ctx.target().gravityMode() == GravityMode.COSINE
        ? Math.cos(ctx.target().measuredSi() - ctx.target().horizontalReferenceSi())
        : 1.0;
  }
}
```

- `driftDeadband = max(1e-3 m/s or 5e-3 rad/s, 3 * velocityNoiseStdDev)`.
- **Resolution: 10 iterations.** The bracket width is `1.6 * kGprior` (from `0.2x` to `1.8x`), and ten halvings land within `1.6 / 2^10 = 1/640` of the range the mechanism's own mass and gearing predict. On this document's elevator (`kGprior = 0.253323 V`) that is `0.405317 / 1024 = 0.000396 V`; on this document's arm (`kGprior ≈ 1.2 V`) it is `1.92 / 1024 = 0.00188 V` — three decimal places, which is what WPILib actually asks for when it says "at least four decimal places" about a number of order 1 V. The old 18 iterations bought five decimal places of a quantity whose *measurement* noise floor is two orders of magnitude larger; it was resolution theater paid for in hard-stop risk and wall-clock time.
- **Total time: 10 iterations x (0.35 s probe + up to ~0.15 s recentre) ≈ 5 s**, plus the 0.5 s sign probe.
- The mechanism never leaves a **3%** band around its starting position, the guard is evaluated every loop *during* the probe, and the supervisor is live throughout with the gravity-aware taper from §7.3.
- If the bracket is exhausted — ten iterations and `|drift|` still above the deadband at both ends — the step reports `RETRY_SUGGESTED` with `"I could not find a holding voltage between 0.0507 V and 0.4560 V, which is the range your mechanism's mass and gearing predict. Either the mass or the gear ratio in your PlantPrior is wrong, or something is binding."` The bracket never silently widens itself.

**The three-angle check (ARM only).** After the 10-iteration bisection at angle `theta_1`, repeat a short **6-iteration** bisection at two more reachable angles `theta_2`, `theta_3` — 6 is enough, because these two probes are only ever used to fit a *phase*, and a phase error of 5 degrees is three orders of magnitude coarser than the voltage resolution. The three bisections plus the two closed-loop moves between angles total about 11 s. For a correct zero convention, the holding voltages must satisfy `V_hold(theta) = kG * cos(theta - horizontalReferenceSi())`. Fit `kG` and a *residual* phase offset `phi` to `V_hold(theta_i) = kG * cos(theta_i - horizontalRef + phi)` by two-parameter least squares over the three points. If `|phi| > 5 degrees`:

> "Your arm's zero is off by about 11 degrees. kG is biggest when the arm points straight out sideways, and your measurements say the sideways point is 11 degrees away from where `RotaryAxis.arm(...)` says it is. Fix the encoder offset — otherwise your arm will droop on one side and creep up on the other, no matter what kP you use."

Nothing in the FRC ecosystem does this, and a wrong arm zero is one of the most common and most confusing mechanism faults. Note that because §6.4's regressor now uses the angle *from horizontal* rather than the raw position, `phi` measures a genuine residual mis-zero rather than the design's own omission.

#### `SysIdSweepStep` — finds kV and kA
Wraps `SysIdSweep` (§6.2). Runs quasistatic forward, quasistatic reverse, dynamic forward, dynamic reverse, with settle and return-to-start between each. Accumulates into `FeedforwardRegression` throughout. The step is presented to the student as **two** logical steps so the teaching is separable:

- **"Find kV"** presents the quasistatic pair, narrates the speed-price lesson, and after the pair shows a provisional kV from a 2-column fit `[sgn(v), v]` over the ramp data only.
- **"Find kA"** presents the dynamic pair, narrates the get-moving-price lesson, and afterwards solves the **full** model over *all four* tests, replacing the provisional kV. The UI says so explicitly: `"kV refined from 5.11 to 5.00 now that we have the step data too."`

This is a small pedagogical trick with real value: the student sees each gain appear from a motion they watched, but the arithmetic is the statistically correct joint fit.

#### `LqrSuggestStep` — proposes kP and kD
No motion. Computes gains from the measured kV/kA and two physical sliders (§9.2), shows them together with the ωn/ζ interpretation panel, and lets the student adjust the sliders and watch the numbers move before committing.

#### `StepResponseStep` — verifies, and optionally refines
Commands a bounded closed-loop step (or a profiled move), records the response into the ring buffer, runs `StepResponseAnalyzer` (§10), and shows the classification plus a recommendation. In `REFINE` mode it applies the deterministic gain update (§9.3) and repeats, up to a bounded iteration count. It is the only caller of `supervisor.abort(AbortReason.UNSTABLE_RESPONSE)` — condition 12 of §7.2.

---

### 8.5 Order of operations

The canonical order taught by the wizard, and the order in which gains are finalized:

```
kS  ->  kV  ->  kA  ->  kG  ->  kP  ->  kD          (feedforward before feedback, always)
```

**Gravity mechanisms need one adjustment, and here is why.** You cannot measure kS on an elevator until gravity is canceled: ramp the voltage from zero and the "motion" you detect is the carriage falling, not friction breaking loose. So the wizard runs a **kG pre-pass** (`HoldBisectionStep`) before the kS step, uses that provisional kG to cancel gravity during the kS/kV/kA sweeps, and then **re-solves kG jointly with kS/kV/kA in the OLS** at the kG step. The student still learns the gains in canonical order; the pre-pass is presented as part of the pre-flight ("first we work out how hard gravity is pulling, so the rest of the measurements aren't fighting it").

> **This is exactly why revision 3's silent `kG = 0` was so damaging, and why §8.4 no longer does it.** With `kG = 0`, `BreakawayRampStep`'s `gravityCompensation()` returns zero, so the kS ramp measures "volts to lift the elevator" and kS comes out about ten times too large — the failure this very paragraph warns about — and the joint OLS then inherits a gravity-shaped residual that corrupts kV and kA as well. A brake-mode arm and a high-reduction gearbox both produced that outcome, silently, on the two archetypes where gravity always exists.

The UI shows the pre-pass value and the final value side by side, and disagreement is itself a diagnostic:

> "Gravity pre-pass said kG = 0.2531 V; the full fit says kG = 0.2528 V. Those agree to 0.1%, which is a good sign that your model is right."

> "Gravity pre-pass said kG = 0.253 V but the full fit says 0.179 V. They should agree. This usually means the mechanism is not purely constant-gravity — a cascading elevator with a constant-force spring, for example — or that something is binding at one end of travel."

Integral gain is never produced by any recipe. It appears only in the `REVIEW` state as an option, gated behind the explanation in §13.7 and `ControlConfig.integral(kI, iZone, iMaxVolts)`.

---

### 8.6 Recipe: FLYWHEEL

**Applies to:** shooter wheels, high-inertia rollers. Velocity control, no gravity.
**Total time:** about 75 seconds. **Space needed:** none.
**WPILib reference answer for the docs' simulated flywheel:** kV = 0.0075, kP = 0.1, kI = 0, kD = 0 — see §13.11 for which of those are checkable answers and which are not.

| # | Step | What the student sees | What the robot does | Math |
|---|---|---|---|---|
| 1 | **Pre-flight** | "Before we tune, let's check the machine. Is the wheel free to spin? Is idle mode set to coast?" | Health check (§7.5), reduced set: idle-mode read-back, sensor liveness, direction, no-gravity confirmation. | — |
| 2 | **Find kS** | "Every mechanism has friction... watch for the exact moment the wheel starts to turn." | `BreakawayRampStep` at 0.30 V/s, both directions if reversible, else forward only. About 8 s. | `kS = mean(u at breakaway)` |
| 3 | **Find kV** | "A spinning motor pushes back... kV is the price of speed." Live plot of velocity vs applied volts. | Quasistatic ramp forward + reverse, `rampRate = Vmax/6`. About 24 s. | 2-column OLS `[sgn(v), v]` over ramp samples |
| 4 | **Find kA** | "Things with mass don't change speed instantly." Live plot showing the spin-up curve. | Dynamic step forward + reverse at `Vstep`, 3 s timeout each. About 14 s. | Full 3-column OLS over **all** samples: `phi = [sgn(v), v, a]` |
| 5 | *(kG skipped)* | "Flywheels don't fight gravity, so there's no kG. Skipping." | nothing — and this is the **only** archetype family for which `kG = 0` is a legitimate wizard output (§8.4) | — |
| 6 | **Suggest kP** | Two sliders: "How much speed error can you live with?" (default 2% of target) and "How many volts may I spend correcting it?" (default 3 V). kP updates live as they drag. | nothing | LQR on `LinearSystemId.identifyVelocitySystem(kV, kA)`; see §9.2 |
| 7 | **Check kP** | Two stacked plots: measured-vs-setpoint on top, commanded volts below — deliberately the same layout as WPILib's browser tutorials. | Closed-loop step from 40% to 70% of max safe velocity, hold 2 s, return. Repeat up to 4 times in refine mode. | `StepResponseAnalyzer`, velocity mode |
| 8 | **kD (usually zero)** | "For a flywheel holding a steady speed, D usually has nothing to do. We'll leave it at zero unless step 7 says otherwise." | nothing, unless the analyzer classified `OVERSHOOT_RING` | §9.3 update rule |
| 9 | **Recovery test** | "This is the one that matters in a match: how fast does it get back to speed after you shoot?" Reports recovery time. | Run to setpoint; command a 1.5 V negative pulse for 120 ms to simulate a game piece; measure time to return within tolerance. | `recoveryTime = t(|error| < tol, sustained 0.2 s) - t(pulse end)` |

**Bang-bang branch.** If `neutralMode()` reports `COAST` and the archetype is `FLYWHEEL`, step 8 offers an alternative:

> "Your wheel is in coast mode, which means you can use a bang-bang controller instead. Bang-bang has no gains at all: it's full voltage when you're below the target and zero when you're above. On a heavy wheel under a varying load it often recovers faster than a P controller. Rootstock will set it up as `BangBangController` output x 12 V plus 0.9 x your feedforward — the 0.9 is deliberate, so the feedforward slightly undershoots and bang-bang only ever has to push, never brake."

The library **refuses** to enable bang-bang unless idle mode is coast, and says why: braking fights the controller and causes destructive oscillation. (Note the symmetry with §7.3.1: a flywheel *must* be coast for its best controller, and a gravity mechanism *must* be brake for a safe abort. Both are read back from the same `neutralMode()` accessor, and both are checked at pre-flight.)

---

### 8.7 Recipe: ELEVATOR

**Applies to:** elevators, telescopes, linear slides. Position control, constant gravity.
**Total time:** about 106 seconds of motion (the gravity pre-pass is 5 s, not 9). **Space needed:** full travel, clear.
**WPILib reference answer for the docs' simulated elevator:** kG = 2.28, kV = 3.07, kA = 0.41, kP = 2.0 — see §13.11. *(That is the WPILib tutorial's plant, which is not this document's plant; §11.5c's worked session is 8793's 12:1 cascade elevator and produces different numbers for good physical reasons.)*

| # | Step | What the student sees | What the robot does | Math |
|---|---|---|---|---|
| 1 | **Pre-flight** | "Soft limits set? Homed? Brake mode? Anything in the way over the full travel?" | Health check, full set. Verifies device soft limits exist, verifies travel range, **reads back idle mode and BLOCKS on coast (§7.3.1)**, direction, backlash, encoder slip. **BLOCKS** on missing soft limits or inverted direction. | — |
| 2 | **Gravity pre-pass** | "First we find out how hard gravity is pulling, so nothing else we measure is fighting it. Watch: the carriage should hang almost perfectly still." | `HoldBisectionStep` at mid-travel: a supervised coast borrow to find which way it falls (or the breakaway-asymmetry fallback), then 10 bracketed probes, about 5 s. Never leaves a 3% band. | Physics-bracketed bisection on drift sign, §8.4 |
| 3 | **Find kS** | "Now, on top of holding it still, how much extra push does it take to break friction loose?" | `BreakawayRampStep` with `kG` applied as an offset. Both directions. About 10 s. | `kS = mean(extra volts at breakaway)` |
| 4 | **Find kV** | "Watch the carriage move at a steady speed. The faster it goes, the more volts it takes." | Quasistatic ramp up and down, `rampRate` derived so the ramp uses at most 70% of the **supervisor band** (§6.2). About 26 s including returns. | Provisional 3-column OLS over ramp data |
| 5 | **Find kA** | "This one is quick and it looks violent. Watch the first quarter-second — that's the only part that tells us about mass." | Dynamic step up and down, `Vstep` derived so the step uses at most 45% of the band in 1.5 s. About 12 s. | — |
| 6 | **Confirm kG** | "Now we solve for all four numbers at once, using everything we just recorded." Shows pre-pass kG vs fitted kG. | nothing | Full 4-column OLS: `phi = [1, sgn(v), v, a]`, `beta = [kG, kS, kV, kA]` |
| 7 | **Build the profile** | "You will never guess a max speed again. This comes straight from kV." Shows the voltage-achievable cruise and checks the team's declared constraints against it. | nothing | `ExponentialProfile.Constraints.fromCharacteristics(0.85 * 12.0, kV, kA)`. Cross-checked against `ElevatorFeedforward.maxAchievableVelocity(11.0, 0)` and `maxAchievableAcceleration(11.0, 0.8 * vMax)`. |
| 8 | **Suggest kP** | Sliders: "How close is close enough?" and "How many volts may I spend?" Panel shows ωn and ζ. | nothing | LQR on `identifyPositionSystem(kV, kA)`, §9.2 |
| 9 | **Suggest kD** | Same panel; kD comes out of the same LQR solve. Narration explains the damper — and, on a high-kV mechanism, explains why it barely matters. | nothing | `K(0,1)` from the same `getK()` |
| 10 | **Check and refine** | Two stacked plots. Classification and a plain-language verdict after every attempt. | Profiled move over 25% of travel (`softMin + 0.35*range` to `softMin + 0.60*range`), hold 1.5 s, return. Up to 6 refine iterations. | §9.3 + §10 |
| 11 | **Full-travel verify** | "Last check: the whole safe band, twice, the way a match would use it." Reports rise time, overshoot, settle time, steady-state error at both ends. | Profiled move `positionMin -> positionMax -> positionMin`. | §10 metrics, reported not acted on |

> **Revision 4 — step 11 sweeps the SUPERVISOR band, not the device soft limits.** Revisions 1 through 3 commanded `softMin -> softMax -> softMin`. Under §7.1's corrected `derive`, the supervisor band is *strictly inside* the soft-limit band by construction, so a verify move to `softMax` is a move to a position the supervisor will abort before reaching — the verify step would have tripped `LIMIT_REACHED` on every mechanism, every time. On this document's elevator the verify now runs 0.0699 m → 1.3272 m → 0.0699 m (1.2573 m of travel, 90.0% of the mechanism's range), and `ElevatorRecipeSimTest` asserts the verify completes without an abort.

**Where the predictions go.** Four `PredictStep`s are interleaved into this recipe, before the `RUNNING` phase of steps **2 (kG)**, **3 (kS)**, **8 (kP)** and **10 (refine)**. They do not renumber the recipe — a `PredictStep` is a sub-phase of the step it precedes, not a twelfth step — and they add no motion and about 15 s of reading. The refine step asks its question again on each iteration where the gains change by more than 20%, so a student who guesses wrong at iteration 1 gets a second, better-informed go at iteration 2. `Predictions: n/m` lands in the report (§11.5c).

**Elevator-specific safety notes carried in the recipe:**
- Steps 4, 5, 10 and 11 run inside the standard `SafetyEnvelope.derive` band, which is `softMargin + max(0.03 * range, floor)` inside the **hard** limits (§7.1) and therefore strictly inside the device's own soft limits **for every legal margin**. The supervisor trips first and the student sees a named reason instead of a silent clamp. This is enforced by `SafetyEnvelopeTest`'s strict inequality over the whole margin range, not by a recipe-local adjustment.
- Step 5 (`kA`) uses a 1.5 s dynamic timeout regardless of the derived value. A dynamic step on a vertical elevator is the single most dangerous motion in the whole recipe.
- If `statorCurrentAmps()` is empty, steps 4, 5, 10, 11 halve `maxVolts` and say so.
- Pre-flight **BLOCKS** on `NeutralMode.COAST` (§7.3.1), because an elevator that coasts falls the moment the student lets go of the trigger.

---

### 8.8 Recipe: ARM / PIVOT

**Applies to:** arms, pivots, wrists, hoods, anything with a cosine gravity term.
**Total time:** about 118 seconds of motion (three-angle gravity pre-pass: one 10-iteration bisection at `theta_1` plus two 6-iteration bisections, ~11 s total). **Space needed:** full swing, clear.
**WPILib reference answer for the docs' simulated vertical arm:** kG = 1.75, kV = 1.95, kP = 5, kD = 1 — see §13.11.

Identical in shape to the elevator recipe, with these differences:

| # | Difference | Detail |
|---|---|---|
| 1 | **Pre-flight adds the zero-convention question** | "Rootstock needs to know the angle at which your arm points straight out sideways. Your config says `RotaryAxis.arm(Degrees.of(0.0))`. Move the arm to horizontal now and tell me what the encoder says." The measured value is compared with `horizontalReferenceSi()`; a mismatch > 5 degrees blocks the recipe with an explanation. |
| 2 | **Pre-flight BLOCKS on coast** | Same rule as the elevator (§7.3.1). An arm that coasts drops onto its hard stop on every `ENABLE_RELEASED`. |
| 3 | **Gravity pre-pass runs at three angles** | The `HoldBisectionStep` three-angle check (§8.4). Chooses the angle closest to horizontal that is inside the safe band as `theta_1`, then `theta_1 +/- 25 degrees` clamped to the band. The move *between* angles is the same closed-loop `recentre()` the bisection uses, not an open-loop command. Fits `V_hold(theta) = kG * cos(theta - horizontalRef + phi)`. Reports both `kG` and the residual phase error `phi`. |
| 4 | **kS is measured near horizontal** | Friction on an arm is roughly angle-independent, but the *gravity cancellation* is only exact if `kG` is right, so the kS ramp is run at `theta_1` where `cos(theta - horizontalRef)` is largest and the cancellation is best conditioned. |
| 5 | **The sweep avoids the vertical singularity** | `cos(theta - horizontalRef)` goes to zero at +/-90 degrees from horizontal, so `kG` is unidentifiable from samples taken only near vertical. The quasistatic ramp is constrained to spend at least 60% of its samples within +/-60 degrees of horizontal. If the arm's travel makes that impossible, the recipe warns: `"Your arm never gets within 60 degrees of horizontal, so kG is hard to measure accurately here. The number we give you will be a best effort."` |
| 6 | **Regressor uses `cos(theta - horizontalRef)`** | `phi = [cos(theta - horizontalRef), sgn(v), v, a]`, `beta = [kG, kS, kV, kA]` — see §6.4's revision-4 note on why the raw position was wrong. |
| 7 | **Profile constraints get an angle-aware cross-check** | `ArmFeedforward.maxAchievableVelocity(11.0, worstCaseAngle, 0)` where `worstCaseAngle` is whichever reachable angle maximizes `|cos(theta - horizontalRef)|`. The exponential constraints from `fromCharacteristics` ignore gravity, so if the achievable velocity at the worst angle is less than 80% of the profile's max velocity, the profile is clamped and the student is told why. |
| 8 | **kP/kD sliders default tighter** | Default max acceptable error 2 degrees, max control effort 5 V. |
| 9 | **Verify sweeps the full arc inside the supervisor band, both directions** | Reports rise/overshoot/settle/SSE separately for the *up* move and the *down* move, because an arm with a wrong kG behaves asymmetrically and this is the clearest way to show it: `"Going up it settles in 0.4 s with 3% overshoot. Coming down it takes 1.1 s and stops 1.8 degrees short. That asymmetry is gravity, not kP - your kG is a little low."` |

---

### 8.9 Recipe: TURRET

**Applies to:** turrets, azimuth stages, counterbalanced hoods. Position control, no gravity.
**Total time:** about 85 seconds.
**WPILib reference answer for the docs' simulated turret:** kV = 0.15, kP = 0.3, kD = 0.05 (feedforward-only answer kV = 0.2; feedback-only answer kP = 0.3, kD = 0.05) — see §13.11.

Same shape as the elevator recipe with the gravity steps removed:

1. Pre-flight (health check; **gravity-present test is a hard check here** — if the mechanism drifts when released, or breaks loose asymmetrically, the archetype is wrong and the recipe stops).
2. Find kS.
3. Find kV.
4. Find kA.
5. *(kG skipped, with the narration in §8.6 step 5. This and `FLYWHEEL`/`STEER`/`DRIVE_VELOCITY` are the only archetypes for which the wizard will output `kG = 0`.)*
6. Build the profile.
7. Suggest kP / kD.
8. Check and refine.
9. Verify: two moves, +90 degrees and -90 degrees (clamped to the supervisor band).

**Turret-specific teaching moment, shown at step 7.** WPILib's turret tutorial makes a point worth repeating verbatim in the UI, because it is the most common conceptual error at this archetype:

> "Feedforward alone can't hold a turret in place. kV and kA describe how much voltage it takes to *move* at a given speed — and when you're sitting still at your target, the answer is zero. That's why a feedforward-only turret gives a little 'kick' when the setpoint changes and then drifts. A position mechanism with no gravity needs feedback to hold. This is the one archetype where kP does most of the work."

**Continuous rotation.** If the turret has more than 360 degrees of travel, `design/01`'s `RotaryAxis.turret(false)` declares it a **bounded** axis (min/max in radians, unwrapped) and **not** continuous. Continuous input is only enabled for `STEER`. The user's 8793 turret (`ShooterSubsystem.java:349-369`) is exactly this case: a >360-degree bounded axis with unwrapping, not a continuous one. The recipe verifies the distinction at pre-flight:

> "This mechanism reports 740 degrees of travel, so it is a bounded axis, not a continuous one. Rootstock will not enable continuous wrapping. If it can actually spin forever, change the archetype to STEER and set `RotaryAxis.turret(true)`."

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
| All modules pointed forward and held | The recipe commands the steer axes to zero and holds them for the whole sweep, through `design/05`'s `RootstockDrive`. |
| Only one module (or one side) under test at a time, unless `allModules()` was selected | Per-module gains are the point; averaging four modules hides a bad one. |
| `DriveSelfCheck` reports no bring-up faults | The tuner consumes a correctly-brought-up module (§2.1); it is not the primary defense against a wrong offset or a wrong invert. |

**Multi-module mode.** `TuningRecipe.driveVelocity().allModules(4)` runs the sweep once with all four modules driven together and fits **four independent regressions** from the same motion. It then reports the spread:

> "Module kV: FL 0.128, FR 0.126, BL 0.131, BR 0.098. BR is 24% off the others. That module has a different gear ratio, a different wheel diameter, or something dragging. Fix the hardware — do not give it a different kP."

That single output would have caught the CD-reported case of *"one rotation motor seems to need an entirely different P value"* in minutes. It also directly attacks the swerve bring-up failure mode the small-team research names as the single largest consumer of small-team software time.

Published starting points are offered as a fallback if the student skips identification, keyed by vendor, because a single default kP is actively harmful across a 5000x spread:

| Vendor / family | drive kP | steer kP | steer kD |
|---|---|---|---|
| SparkMax / NEO (YAGSL defaults) | 0.0020645 | 0.01 | 0 |
| TalonFX / Kraken / Falcon (YAGSL defaults) | 1.0 | 50.0 | 0.32 |
| CTRE Tuner X generated (`Slot0Configs`) | 0.1 (kS 0, kV 0.124) | 100 (kS 0.1, kV 1.91, kD 0.5) | — |

These are shown **as vendor-native numbers with a warning that Rootstock's own gains are in volts-per-SI and are not comparable**, and are offered only as "make it move so you can start" values.

---

### 8.11 Recipe: STEER

**Applies to:** swerve steer/azimuth motors.
**Delegates to:** the TURRET recipe, plus continuous input.
**Total time:** about 70 seconds. **Space needed:** none (steer may be tuned on blocks — and *should* be).

Differences from TURRET:

1. `PIDController.enableContinuousInput(-Math.PI, Math.PI)` is enabled by `Controllers.pid(g, /* continuous */ true, ...)` because `design/01`'s `RotaryAxis.isContinuous()` is true for this axis.
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
package org.rootstock.tuning.wizard;

import org.rootstock.control.MechanismArchetype;

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
   * straight from {@link org.rootstock.tuning.FeedbackDesigner} at the archetype defaults and are
   * labeled {@code WIZARD_LQR} with no {@code WIZARD_REFINE} pass.
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
| `RobotIdentity.current() == RobotId.SIM` | `TEACHING`, always, not overridable per-mechanism | Simulation is free, unqueued, and cannot break anything. This is where the learning is supposed to happen, and §13.11 already tells students to practice here. |
| Real hardware, first time this mechanism has ever been tuned | The wizard **asks**, once, on one screen: *"Full walk-through (about eight minutes, teaches you what each number means) or express (about ninety seconds, just measures them)?"* | A student who has never tuned this mechanism should be offered the lesson, not silently given the shortcut. |
| Real hardware, afterwards | The remembered choice, stored per mechanism in `gains.json` under `"preferredMode"` | The fourth mechanism of the day, and every re-tune after a bearing change, is express by default — which is the case OQ#10 raised. |

The wizard never *hides* the other mode: the state screen always shows `Mode: EXPRESS (Y for the full walk-through)`.

**Why this does not undermine the pedagogy.** A student who ran the teaching recipe on the elevator in simulation on Tuesday has already learned what kV is. Making them re-read the kV lesson on the arm on Saturday at an event is not teaching, it is tax, and tax is what makes teams turn a feature off. The `Predictions: n/m` line (§8.4) is what actually measures whether learning happened, and it is measured in `TEACHING` mode where it belongs.

---

## 9. Assisted kP/kD tuning

### 9.1 The algorithm choice, and why

**We ship: LQR-derived initial gains from measured kV/kA, followed by bounded step-response iterative refinement. We do not ship relay (Astrom-Hagglund) autotune, and we do not ship Ziegler-Nichols.**

The rejection is on two grounds, and both go in the docs so the objection is pre-empted:

1. **Safety.** Both relay autotune and Ziegler-Nichols work by *deliberately driving the loop into sustained oscillation* and measuring the resulting limit cycle. On a geared FRC arm or elevator, sustained oscillation means repeatedly slamming a hard stop with the full inertia of the mechanism, at a frequency chosen by the algorithm rather than by anyone watching. There is no version of this that a library aimed at unsupervised 14-year-olds should ship. Every abort condition in §7.2 exists to *prevent* the exact behavior relay autotune requires.
2. **It is worse, and the community already knows it.** The FRC-specific discussion of relay autotune concludes that hand tuning is *"usually rated as superior to the autotune relay method"* and that auto-tune *"is not for the uninitiated"*; the thread was redirected to SysId as the proper answer. Shipping a known-inferior, known-dangerous method as the headline feature would be indefensible.

The chosen method has neither problem. LQR is a closed-form solve on a model we already measured — **zero motion required** to produce the initial gains. The refinement loop only ever runs bounded, profiled moves inside the supervisor's envelope, and its update rules are monotone and bounded. The community's stated objection to autotuning is *pedagogical* — that it hides understanding — and this method answers that directly: every number is derived from a physical quantity the student chose, and every iteration is explained.

### 9.2 `FeedbackDesigner` — LQR from measured kV/kA

This is the highest-leverage feature in the domain and it needs no new math. SysId's own Feedback Analysis view derives kP/kD via LQR from kV/kA plus max-acceptable-error, max-acceptable-control-effort, and measurement delay. Every piece is in wpimath. The only thing missing was somebody doing it on the robot.

```java
package org.rootstock.tuning;

import edu.wpi.first.math.VecBuilder;
import edu.wpi.first.math.controller.LinearQuadraticRegulator;
import edu.wpi.first.math.system.plant.LinearSystemId;
import org.rootstock.control.MechanismArchetype;
import org.rootstock.control.TravelLimits;

/**
 * Derives kP and kD from measured kV/kA using LQR - the same relationship SysId's
 * "Feedback Analysis" view uses, computed on the robot with no laptop round trip.
 *
 * <p>The student's two knobs are physical quantities, not abstract gains:
 * "how much error can you live with" and "how many volts may I spend correcting it".
 * Smaller acceptable error or larger acceptable effort both produce larger gains.
 *
 * <p>The HAL-free solve lives in {@code org.rootstock.pure.solvers.LqrDesign}; this class is the
 * wpimath-backed wrapper plus the student-facing preferences, the warnings, and the panel text.
 */
public final class FeedbackDesigner {

  /** What the student actually chooses. */
  public record Preferences(
      double maxAcceptableErrorSi,          // meters or radians (position) / m/s or rad/s (velocity)
      double maxAcceptableVelocityErrorSi,  // position loops only; defaults to 10x the position error
      double maxControlEffortVolts,         // must be < nominal; the UI clamps at 12
      double measurementDelaySeconds,       // see 9.2.1
      double dtSeconds) {

    public static Preferences defaultsFor(MechanismArchetype a, TravelLimits limits, double toleranceSi) {
      double err = Math.max(Double.isFinite(toleranceSi) ? toleranceSi : 0.005 * limits.range(), 0.002);
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
> **[UNVERIFIED]** The exact Q and R matrices SysId's own Feedback Analysis constructs internally could not be read from `sysid`'s C++ source; the construction above is inferred from WPILib's prose (*"via LQR"*, *"Max Acceptable Error"*, *"Max Acceptable Control Effort"*) plus the public wpimath API. The relationship is Bryson's rule — `Q = diag(1/qelms^2)`, `R = diag(1/relms^2)` — which is what the `Vector` overload of the constructor documents ("maximum desired error tolerance for each state" / "maximum desired control effort for each input"). Rootstock's numbers may therefore differ slightly from SysId's for the same inputs. This is stated in the UI: *"These are educated starting points, not final answers"* — WPILib's own framing.

#### 9.2.0 The closed form, and what it means for the student

For the plant `identifyPositionSystem(kV, kA)` — `A = [[0,1],[0,-a]]`, `B = [[0],[b]]` with `a = kV/kA` and `b = 1/kA` — the **continuous-time** Bryson-weighted LQR has a closed form that is worth writing down, because one half of it is the single most useful sentence in this whole section:

```
Let  q1 = 1/eMax^2,  q2 = 1/vMax^2,  r = 1/uMax^2,   P = a^2 + (q2/r) * b^2,   Q = (q1/r) * b^2

    kP = sqrt(Q) / b = sqrt(q1 / r) = uMax / eMax
    kD = ( sqrt( P + 2*sqrt(Q) ) - a ) / b
```

**`kP` is exactly "how many volts, divided by how much error."** That is not a coincidence and it is not an approximation of the continuous solve — it falls straight out of the symmetric root locus, whose closed-loop poles are the stable roots of `r*s^4 - (r*a^2 + q2*b^2)*s^2 + q1*b^2 = 0`, whose product is `b*sqrt(q1/r)` and which is therefore `b*kP`. A student who is told *"four volts, one centimeter"* has, without knowing it, already said *"kP = 400 V/m"*, and that is a far better thing to teach than "the solver produced 400."

> **[UNVERIFIED against WPILib's discrete implementation.]** `LinearQuadraticRegulator` **discretizes** the plant at `dtSeconds` before solving. The identity above is exact for the continuous ARE and is approached as `dt -> 0`; it departs from the returned value when the desired closed-loop bandwidth approaches the Nyquist rate of the loop. `FeedbackDesignerTest` therefore asserts **monotonicity** against the returned `getK()` (halving `maxAcceptableError` increases kP; doubling `maxControlEffort` increases kP; a positive `latencyCompensate` delay reduces kP) and asserts the closed-form identity only against the pure `LqrDesign` continuous solver, where it is exact. The panel prints the value WPILib actually returned, never the closed form.

#### 9.2.1 Measurement delay

Getting this wrong is the main way LQR-derived gains oscillate on real hardware.

| `ControlLocation` | `runsOnMotor()` | Delay used | Rationale |
|---|---|---|---|
| `ON_MOTOR_PROFILED` | true | `0.0` | Profile and feedback both on the device at ~1 kHz. The device's own filters are accounted for by the vendor; WPILib says smart-motor-controller filters are auto-handled. |
| `ON_MOTOR_DIRECT` | true | `0.0` | Same device loop, no profile. |
| `RIO_PROFILE_MOTOR_LOOP` | true | `0.0` | The *profile* is stepped on the RIO but the *feedback* is on the device, and it is the feedback loop's delay that matters here. |
| `RIO_FULL`, no user filter | false | `0.5 * dtSeconds` = 10 ms | One-sample transport plus zero-order hold. |
| `RIO_FULL`, user declared an N-sample moving average at period T | false | `T * (N - 1) / 2` | The formula WPILib documents for a windowed filter. |

`TuningTarget.measurementDelaySeconds()` overrides the table; when it returns `NaN` (the default), the table applies. This is the **only** question the tuning system asks of `ControlLocation`, which is exactly why **D5** deleted the two-value `LoopLocation` `[SUPERSEDED-NAME]` that revisions 1 through 3 defined alongside it: four values carry strictly more information, `RIO_PROFILE_MOTOR_LOOP` genuinely differs from both of the old two, and requiring a team to type a second name for the same axis was the one place this library shipped the duplicate vocabulary it exists to delete.

#### 9.2.2 The UI panel — and why it does not just print a number

This is the kP step. It is the gain students most need intuition about, and in the first version of this design it was the one delivered as an oracle: two sliders in, a number out of a solver whose Q/R construction is `[UNVERIFIED]`, and `Lessons.P` sitting next to it explaining in prose what a P gain *is* with no bridge to the arithmetic. That is the weakest point in the whole wizard as a teaching artifact, and the fix is cheap: **print the second-order interpretation, derived from the same numbers, above the gains.**

```
  Max error I can live with     [====|--------]   0.050 m
  Max volts I'll spend on it    [========|----]   4.0 V
  Max speed error (advanced)                      0.500 m/s
  Measurement delay                                0 ms  (loop runs on the motor controller)

  With these gains your elevator behaves like a spring that would bounce
  at 5.81 Hz, damped to 2.27.
  Above about 0.7 you will not see it bounce at all -- and this one is nowhere near 0.7,
  because your mechanism's own kV is doing the damping.

      kP = 80.00  V/m          kD = 4.93  V/(m/s)

  Smaller error or more volts -> bigger gains, a faster bounce, and less damping.
  These are educated starting points, not final answers.
```

**The arithmetic, stated so it can be tested.** Model the closed loop as the measured plant `kA * a + kV * v = u` under PD control. Substituting `u = kP*e + kD*edot` gives the standard second-order form, so:

```
wn   = sqrt(kP / kA)                        rad/s      -> natural frequency
fn   = wn / (2 * PI)                        Hz         -> "it would bounce at 5.81 Hz"
zeta = (kD + kV) / (2 * sqrt(kP * kA))                 -> damping ratio
```

**Worked, on this document's elevator** (`kV = 5.000`, `kA = 0.060`, sliders 0.050 m / 0.500 m/s / 4.0 V):

```
a  = kV / kA = 5.000 / 0.060 = 83.3333          b = 1 / kA = 16.66667
q1 = 1/0.050^2 = 400    q2 = 1/0.500^2 = 4      r = 1/4.0^2 = 0.0625
kP = uMax / eMax = 4.0 / 0.050                            = 80.00     V/m
Q  = (q1/r) * b^2 = 6400 x 277.7778 = 1 777 778 ; sqrt(Q) = 1333.333
P  = a^2 + (q2/r)*b^2 = 6944.444 + 64 x 277.7778          = 24 722.22
kD = (sqrt(24 722.22 + 2 x 1333.333) - 83.3333) / 16.66667
   = (sqrt(27 388.89) - 83.3333) / 16.66667
   = (165.4959 - 83.3333) / 16.66667                      = 4.93      V/(m/s)

wn   = sqrt(80.00 / 0.060)   = sqrt(1333.333) = 36.5148 rad/s
fn   = 36.5148 / (2*PI)                       =  5.8116 Hz     -> "5.81 Hz"
zeta = (4.93 + 5.000) / (2 * sqrt(80.00 x 0.060))
     = 9.93 / (2 x 2.190890) = 9.93 / 4.381780 =  2.2662       -> "2.27"
```

Two things about that `zeta` expression are worth saying out loud to the reader of this document, because they are the reason it teaches something a slider cannot:

1. **`kV` is in the numerator.** The mechanism's own back-EMF is damping, for free, before kD does anything. **This elevator is the case in point:** with `kD` set to zero, `zeta = 5.000 / 4.381780 = 1.141` — still comfortably above 0.7, still no visible bounce. On a high-kV mechanism a student watches `zeta` sit above 0.7 with `kD = 0` and learns *why* the flywheel recipe leaves kD at zero (§8.6 step 8) without being told. It is also the answer key to §8.4's worked `PredictStep`.
2. **`kP` is under a square root in `wn` and in the denominator of `zeta`.** Tripling kP makes it 1.732× faster and 1.732× *less* damped, simultaneously. That single sentence is the entire content of the `OVERSHOOT_RING` diagnosis, available before any motion happens.

**This is the same arithmetic `PredictStep` uses** to compute the correct answer to its questions (§8.4). The student is shown the formula's output, then asked to predict from it, then shown the real response. That loop — see it, predict it, watch it — is the difference between the wizard teaching and the wizard narrating.

`LqrPanelMathTest` asserts both formulas against `LqrSuggestStep`'s implementation, per §13.12: an analytic second-order plant with known `wn` and `zeta` is fed through `FeedbackDesigner` and the panel's reported values must match to 1e-9. If someone changes the panel text without changing the maths, or the maths without the text, the build fails.

Sanity warnings emitted alongside — **each with the value it takes on the worked example above, so the table itself is checkable:**

| Warning | Condition | On the worked example |
|---|---|---|
| Saturation | `kP * stepMagnitude > voltage ceiling`, where `stepMagnitude` is the refinement move of §9.3 (`0.25 * range`), **not** the slider | **Fires.** `80.00 x 0.34925 = 27.94 V` against a 12 V ceiling → *"These gains saturate at 12 V for any error bigger than 0.150 m. That's fine with a motion profile, and this mechanism has one."* |
| Noisy derivative | `kD > 0.5 * kV` | **Fires.** `4.93 > 2.50` → *"kD is large compared to kV. On a noisy encoder that will make the mechanism buzz. Consider accepting a little more speed error."* |
| Over-damped | `zeta > 2.0` | **Fires.** `2.27` → *"Damping is 2.27. Nothing will overshoot, but this will arrive lazily and the next step will probably call it SLUGGISH. Loosen the speed-error slider, or accept a bigger position error, if you want it snappier."* |
| Under-damped | `zeta < 0.4` | does not fire → *"Damping is 0.31. This will visibly bounce. That is a legal choice if you want speed, but the next step will classify it OVERSHOOT_RING and offer to add kD."* |
| Aliasing | `fn > 0.4 / dtSeconds` (the loop samples the bounce fewer than about 2.5 times per cycle) | does not fire (`5.81 Hz` against a `20 Hz` limit at `dt = 0.020`) → *"These gains want the mechanism to bounce at 22 Hz, and your loop only runs at 50 Hz. The controller cannot see an oscillation that fast, so it will amplify it instead of damping it. Accept more error, or move this loop onto the motor controller."* |
| Non-physical | `kP <= 0` | hard failure; means kV or kA came out non-physical |

> **Revision 4 — the saturation warning was previously dead code.** Revisions 1 through 3 wrote it as `kP * maxAcceptableError > maxControlEffort * 1.5`. By §9.2.0's identity, `kP * maxAcceptableError == maxControlEffort` **exactly** for `forPosition`, so the ratio is always 1.0 and the warning could never fire for the archetype it mattered most to. It is now written against the *step magnitude the refinement loop will actually command*, where it does real work — and on the worked example above it correctly fires.

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
    gainSink().apply(g)
    run one bounded step:
        position: profiled move  p0 -> p0 + stepMagnitude,  hold settleWindow,  return to p0
        velocity: setpoint 0.40*vMax -> 0.70*vMax,          hold settleWindow,  return
      (supervisor live throughout -- check() is the first statement of every periodic();
       any abort ends refinement immediately)
    record into ring buffer
    verdict = StepResponseAnalyzer.analyze(buffer, setpointTrace, tolerance, expectedRiseTime)
    score   = cost(verdict)                                  # see below
    IF score < bestScore:  bestScore = score;  best = g
    IF verdict.classification == GOOD:  BREAK
    IF verdict.classification == UNSTABLE:
        g = g0.withKp(0.4 * g.kP()).withKd(0.4 * g.kD())     # hard retreat
        supervisor.abort(UNSTABLE_RESPONSE)                  # condition 12; requires re-arm
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
| `OVERSHOOT_RING` | `kD == 0` | `kD = 0.35 * max(kD_lqr, kV)` | — | "It overshoots and rings. That's a spring with no shock absorber - adding some kD." |
| `OVERSHOOT_RING` | `kD > 0`, first two occurrences | `kD *= 1.5` | `kD <= 4 * kD_lqr` | "Still ringing. More damping." |
| `OVERSHOOT_RING` | `kD > 0`, third occurrence | `kP *= 0.7` | — | "More damping isn't helping, so the spring is just too stiff. Cutting kP by 30%." |
| `OSCILLATING` | `oscHz < 8 Hz` | `kP *= 0.6`, `kD *= 0.8` | — | "It's swinging back and forth about twice a second. That's a kP that's too high. Cutting it by 40%." |
| `OSCILLATING` | `oscHz >= 8 Hz` | `kD *= 0.5` | — | "It's buzzing at 14 Hz. That's too fast to be the mechanism - kD is amplifying sensor noise. Halving kD." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, position mechanism, error in the direction of the last motion | `kS += clamp(0.6 * abs(residualVolts), 0, 0.20)` | `kS <= 0.25 * nominal` | "It stops just short every time, and the controller is holding a steady 0.31 V trying to close the gap. That 0.31 V is friction your kS isn't paying for. Raising kS." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, gravity mechanism, error consistently *downward* | `kG += clamp(0.6 * abs(residualVolts), 0, 0.30)` | `kG <= 0.6 * nominal` | "It settles 8 mm low every time and holds 0.22 V doing it. That's gravity your kG isn't paying for. Raising kG." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` present**, neither signature | `kP *= 1.4` | `kP <= 8 * kP_lqr` | "There's a small offset that doesn't look like friction or gravity. Nudging kP up. If this doesn't clear it, read the note about kI." |
| `STEADY_STATE_ERROR` | **`getFeedbackVolts()` empty** | **no gain change**; step ends `RETRY_SUGGESTED` | — | "This mechanism's loop runs on the motor controller and does not report how it split feedforward from feedback, so I cannot tell you whether the leftover error is friction or gravity. Run this mechanism with `ControlLocation.RIO_FULL` for one session if you want that diagnosis." |
| `GOOD` | — | stop | — | "That's a good response. Rise 0.31 s, 1.4% overshoot, settled in 0.47 s, final error 2 mm." |
| `UNSTABLE` | — | retreat and `abort(UNSTABLE_RESPONSE)` | — | "Stopping. The response was growing instead of settling, which is how mechanisms break. kP has been cut to 40% of what it was. Press Retry when you're ready." |

> **Revision 4 — one message, one name.** `[SUPERSEDED-NAME]` The `getFeedbackVolts()`-empty row previously read *"`LoopLocation.ON_CONTROLLER`, which the mechanism builder calls `ControlLocation.RIO_FULL`"* — the library that exists to kill duplicate vocabulary, teaching a student both names for one thing in one error message. **D5** deletes `LoopLocation`; the student sees the name that is in their own config file, and only that name.

#### 9.3.1 `residualVolts` — the quantity, and when it does not exist

`residualVolts` is the key quantity in the steady-state rules and deserves the explicit definition:

```
residualVolts = mean over the last 0.5 s of target.getFeedbackVolts()
```

i.e. exactly the voltage the *feedback* term is holding to keep the mechanism where it is. In steady state that voltage is, by definition, the feedforward term that is missing. Adding 60% of it to kS or kG (rather than 100%) keeps the loop from over-correcting and oscillating between iterations. This is the single most useful piece of arithmetic in the whole refinement loop, and it is why Rootstock can tell a student *"add kS"* instead of *"add kI"*.

**It is not always computable, and the design says so instead of pretending.** The original definition was `mean(commandedVolts - feedforwardVolts)`, which quietly assumed the robot could see both halves. When `controlLocation().runsOnMotor()` — the library's recommended default, `ControlLocation.ON_MOTOR_PROFILED`, and the setting `design/01`'s builder defaults to for every smart controller — the feedforward is computed *inside* the Talon or the Spark and `TuningTarget` exposed only the applied voltage, which is the sum. `feedforwardVolts` was not computable, so all three `STEADY_STATE_ERROR` rules were dead on the default configuration and the wizard fell through to the `kP *= 1.4` catch-all — which is precisely the "reach for kP/kI instead of kS/kG" mistake §13.7 says this library exists to prevent.

The fix is `TuningTarget.getFeedbackVolts()` (§3.1), an `Optional<Double>` the vendor adapter fills in when it genuinely can:

**Phoenix 6.** The split is directly reported. `TalonFX` exposes both halves as `StatusSignal<Double>`:

```java
@Override public Optional<Double> getFeedbackVolts() {
  if (!m_closedLoopSignalsSubscribed) return Optional.empty();
  return Optional.of(
      m_leader.getClosedLoopOutput().getValueAsDouble()
    - m_leader.getClosedLoopFeedForward().getValueAsDouble());
}
```

**Signature verified 2026-08-07** against the Phoenix 6 Java API docs ([CoreTalonFX](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/hardware/core/CoreTalonFX.html)): `StatusSignal<Double> getClosedLoopOutput()` and `StatusSignal<Double> getClosedLoopFeedForward()` both exist, with `(boolean refresh)` overloads, documented as *"Closed loop total output"* and *"Feedforward passed by the user"* respectively.

Two notes the adapter must honor:

1. **[UNVERIFIED — inferred, needs a bench check]** The javadoc does **not** state the units of `getClosedLoopOutput()`. We infer that it carries the units of the active control request's output type, which makes it volts only for voltage-output requests (`PositionVoltage`, `VelocityVoltage`, `MotionMagicVoltage`, `MotionMagicExpoVoltage`). The adapter therefore returns `Optional.empty()` for duty-cycle and torque-current requests rather than guessing — the conservative direction, since a wrong scale factor here would feed a fabricated `residualVolts` straight into the kS/kG update rules. Someone with a Kraken on a bench must confirm the voltage-request case reads back in volts before 0.1 ships; this is open question 12.
2. Neither signal is in the default subscribed set, so the Phoenix adapter adds them via `BaseStatusSignal.setUpdateFrequencyForAll(50, ...)` **only when `TuningRegistry` has registered this target**. A team that never tunes pays no bus bandwidth.

**REVLib.** REV exposes no equivalent signal. The adapter computes the feedforward half in Java from the canonical `Gains` and the current profile state — which it already has, because it is the code that fed the arbitrary feedforward to the controller in the first place — and subtracts:

```java
@Override public Optional<Double> getFeedbackVolts() {
  double ff = m_feedforward.calculate(
                  m_measuredSi - m_horizontalReferenceSi,      // radians FROM HORIZONTAL
                  m_profileState.velocity, m_nextProfileState.velocity)
            + (m_gravityMode == GravityMode.NONE
                 ? 0.0
                 : m_gains.kG() * gravityShape());
  return Optional.of(m_appliedVolts - ff);
}
```

This is a *reconstruction*, not a measurement, and `describeConversion()` says so verbatim so the student is never misled about which one they are looking at:

```
Wrist gain conversion (REVLib, voltage-compensated 12.0 V, ControlLocation.ON_MOTOR_PROFILED)
  ...
  NOTE: REVLib does not report how the controller split feedforward from feedback.
        Rootstock reconstructs the feedforward half in Java from your kS/kV/kA/kG and the
        profile state, so "residual volts" here is computed, not measured. On a Phoenix 6
        device the same number is read directly off the motor controller.
```

**When it is empty.** `GenericMotorIO` targets, torque-current and duty-cycle Phoenix requests, and any hand-rolled adapter that does not override the default all return `empty()`. In that case the wizard **skips the kS/kG rules entirely** — it does not guess, and it does not silently fall through to kP. It shows the message in the table above, ends the step `RETRY_SUGGESTED`, and points at the one-line change that would restore the diagnosis. `StepResponseAnalyzer` still reports `steadyStateErrorSi` (which needs only position), and `ResponseVerdict.residualVolts` is `NaN` rather than a fabricated zero. `RefinementRuleTest` (§16.1) covers both branches: present and empty.

**Safety properties of the refinement loop, stated explicitly because reviewers will ask:**

1. It never issues an unprofiled step to a position mechanism. Every move is a `TrapezoidProfile` or `ExponentialProfile` bounded by constraints derived from measured kV/kA.
2. Step magnitude is 25% of travel and starts from `softMin + 0.35 * range`, so the mechanism is never near either hard stop, and both ends are inside the supervisor band (§7.1) by construction — asserted by `RefinementBandTest` over the same margin sweep `SafetyEnvelopeTest` uses.
3. Every iteration runs inside `TuningSupervisor` with the full abort list live, because `StepResponseStep.periodic()`'s first statement is `supervisor.check()`. A single `LIMIT_APPROACH` ends refinement and reverts to `best`.
4. Gains only move by bounded multiplicative factors within hard caps relative to the LQR baseline. There is no path by which the loop can produce a kP an order of magnitude above the physics-derived value.
5. Maximum six iterations, each at most `2 * (profileTime) + 1.5 s`, so the worst case is bounded at about 45 s.
6. The `UNSTABLE` classification is a *terminal* state that requires the student to physically re-pull the trigger. There is no automatic retry after instability.
7. The trigger must be held for the entire loop. Letting go stops everything, the best-so-far gains are kept, and `abort()` restores the idle mode before neutralling (§7.3).

**An arm must never slam a hard stop.** The properties above are what make that true, and the arm recipe adds one more: for `ARM`, `stepMagnitude` is additionally clamped so the move stays within +/-45 degrees of the starting angle, and the refinement start position is chosen as the point in the safe band furthest from both limits. An `ARM` whose safe band is smaller than 20 degrees is refused with `"There isn't enough safe travel here to test a step response. Widen your soft limits or tune this one by hand."`

---

## 10. Response diagnostics — the teaching layer

This is what turns a plot into a lesson. Given a recorded step (or profiled move), classify it and say something a 14-year-old can act on.

### 10.1 The sample buffer

```java
package org.rootstock.tuning.sysid;

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

`t` comes from `Clock.seconds()`, never from `Timer` (§5.6 rule 1).

### 10.2 The metrics — actual math

Let the analyzed window be samples `i = 0..N-1` with time `t_i`, measurement `y_i`, setpoint `r_i`. Let `y0 = y_0`, `r = r_{N-1}` (final setpoint), and `D = r - y0` the commanded change. Define the normalized response `e_i = (y_i - y0) / D`. If `|D|` is below `4 * tolerance`, the step is too small to analyze and the analyzer returns `INSUFFICIENT_EXCITATION`.

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

`SampleBuffer.Sample.feedbackVolts` is `NaN` for the whole window when `getFeedbackVolts()` is empty, and every consumer treats `NaN` as "unknown," never as zero. The `/RootstockTuner/plot/ffVolts` and `/plot/fbVolts` topics publish `NaN` too, so the two-line graph visibly *stops* rather than drawing a flat zero that a student would read as "feedback is doing nothing."

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

**Worked, on this document's refinement step** (0.34925 m move, `MotionConstraints.of(1.6, 6.0)`):

```
distance to reach cruise = v^2 / (2a) = 1.6^2 / 12 = 0.213333 m ; x2 = 0.426667 m > 0.34925 m
   -> the profile is TRIANGULAR, cruise is never reached
peak velocity  = sqrt(a * d) = sqrt(6.0 x 0.174625) = sqrt(1.047750) = 1.023597 m/s
profileDuration= 2 x 1.023597 / 6.0                                  = 0.341199 s
expectedRise   = 0.80 x 0.341199                                     = 0.272959 s
expectedSettle = 0.341199 + 0.25                                     = 0.591199 s
```

### 10.3 Classification — ordered rules

Evaluated top to bottom; first match wins. This ordering matters: instability must be caught before anything else, and a mechanism that never arrives must not be classified on its (nonexistent) overshoot.

```java
package org.rootstock.tuning.diagnostics;

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
| 5 | `SLUGGISH` | `riseTime` is NaN **OR** (`riseTime > 2.5 * expectedRise` **AND** `overshootPct < 3`) |
| 6 | `GOOD` | `overshootPct <= 8` **AND** `abs(sse) <= max(tolerance, 0.02 * abs(D))` **AND** settled **AND** `settleTime <= 1.75 * expectedSettle` |
| 7 | fallback | If none match, report `OVERSHOOT_RING` if `overshootPct > 8`, else `SLUGGISH`. Never return "unknown" to a student. |

### 10.4 `ResponseVerdict` and the plain-language layer

```java
package org.rootstock.tuning.diagnostics;

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
package org.rootstock.tuning.diagnostics;

/**
 * Pure, HAL-free. Reusable by design/06 to answer "is this mechanism still tuned?"
 * The metric core lives in org.rootstock.pure.solvers; this class is the thin typed wrapper.
 */
public final class StepResponseAnalyzer {

  public static ResponseVerdict analyze(
      java.util.List<org.rootstock.tuning.sysid.SampleBuffer.Sample> window,
      double toleranceSi,
      double expectedRiseSec,
      double expectedSettleSec,
      double voltageCeiling) { /* ... */ return null; }
}
```

**The actual recommendation text**, one entry per class. These strings ship in `Coach` and are published to `/RootstockTuner/result/verdict` and `/recommendation`. Numbers in braces are substituted.

| Class | `diagnosis` | `recommendation` |
|---|---|---|
| `GOOD` | "Nice. It got there in {riseTime} s, overshot by {overshootPct}%, settled in {settleTime} s, and finished {sse} off target — inside your tolerance of {tolerance}." | "Nothing to change. Press A to keep these gains." |
| `SLUGGISH` | "It's heading the right way, just lazily. It took {riseTime} s to cover 10% to 90% of the move; for this mechanism that should be closer to {expectedRise} s. There's no overshoot at all, which means kP is doing less than it could." | "Turn kP up. Rootstock will multiply it by 1.6 and try again — press A, or press B to change it yourself." |
| `OVERSHOOT_RING` | "It overshoots by {overshootPct}% and then rings {crossings} times before settling. Damping ratio came out at {zeta} — anything under about 0.7 will visibly bounce. That's a stiff spring with no shock absorber." | "Add damping: cut kP by 30% **or** add kD. Rootstock will try kD first, because that keeps the mechanism fast. Press A." |
| `OSCILLATING` (low freq) | "It's swinging back and forth {oscHz} times a second and not settling. Damping ratio {zeta}. This is a kP that's too high for this mechanism — the spring is so stiff it throws the mechanism past the target every time." | "Cut kP by 40%. Press A. If it still oscillates after two tries, check for backlash — no gain can fix slop." |
| `OSCILLATING` (>= 8 Hz) | "It's buzzing at {oscHz} Hz. That's far too fast to be the mechanism itself moving — it's kD amplifying noise in your encoder reading and feeding it back into the motor." | "Halve kD. Press A. If the buzz persists at kD = 0, your encoder is noisy or your velocity signal is being filtered somewhere you don't know about." |
| `STEADY_STATE_ERROR` (friction signature) | "It settles {sse} short of the target and just sits there. The controller is holding {residualVolts} V trying to close that gap. That voltage is friction — and friction is exactly what kS is for." | "Raise kS by {delta} V. Do **not** reach for kI: a constant offset means a feedforward term is missing, and adding an integrator hides the problem instead of fixing it." |
| `STEADY_STATE_ERROR` (gravity signature) | "It settles {sse} low, every time, in the direction gravity pulls. The controller is holding {residualVolts} V just to stop it sinking further. That's gravity your kG isn't paying for." | "Raise kG by {delta} V. If raising kG makes it settle *high* on the way down but still low on the way up, your arm's zero angle is wrong — run the gravity pre-pass again." |
| `UNSTABLE` | "Stopped. The oscillations were getting bigger, not smaller ({A1} then {A2}). That's how mechanisms break." | "kP has been cut to 40% of what it was and the routine is disarmed. Pull the trigger again when you're ready to retry. If this happens twice, your kV or kA measurement is probably wrong — re-run the identification steps." |
| `INSUFFICIENT_EXCITATION` | "That move was too small to learn anything from — it only traveled {D}, and your tolerance is {tolerance}." | "Nothing changed. Increase the step size, or widen your tolerance if {tolerance} is unrealistically tight." |
| any, with `saturated == true` | (appended) "Also: the motor was commanded to its {ceiling} V ceiling for {duration} s during this move. While it's saturated, kP and kD do nothing at all — the mechanism is just going as fast as it can." | (appended) "Either use a motion profile so the setpoint stays reachable, or make the step smaller." |

The saturation append is important and almost always missing from hand tuning: a student watching a saturated response draws conclusions about gains that were not in the loop at the time.

### 10.5 Where the verdict shows up

- Live in the wizard's `REVIEW` state.
- In the markdown tuning report (§11.5c), with the numbers.
- As a `HealthSource` check between matches: `TuningHealth.check(target)` runs a single small profiled move during a pit test and raises `Alerts.warning("Tuning", ..., MatchImpact.PIT_ONLY)` if the classification has degraded from `GOOD` since the last tuning session. Belts stretch, batteries age, and a mechanism that was tuned in week 1 is often not tuned by champs. **D23**: `TuningHealth` is the half of this domain that `design/06` consumes; `MechanicalHealthCheck` stays here because it commands voltage.

---

## 11. Gain persistence

> **Naming (revision 4).** `[SUPERSEDED-NAME]` The classes are **`TunedValueStore`** and **`ValueExporter`**. `DESIGN.md` §12.1a's M6 row, [`ROADMAP.md`](../ROADMAP.md) §5's M6 gate condition, and `DESIGN.md` §7's package tree all name them that way; revisions 1 through 3 of this document called them `GainStore` and `GainsExporter`, and the canonical names appeared **zero** times here. Same-concept-two-names is the specific bug class items 2 and 3 of the defensible core exist to delete, so the domain document conforms rather than the four summary documents. `TunedValueStore` is also the store `DESIGN.md` §5.6 names for persisted **setpoints** keyed by `RobotId`, which is why it is not called a *gain* store: it persists tuned values, and gains are one kind.

### 11.1 The problem

Nothing in the FRC ecosystem closes this loop. 6328's `LoggedTunableNumber` reads the dashboard and falls back to a compile-time default; nothing writes back. WPILib `Preferences` persists to flash but is a separate, flat, manually-wired system with its own keys. DogLog mirrors tunables into the DataLog, which is post-hoc analysis, not persistence. The result is the failure every team knows: *we tuned it, then power-cycled, and lost everything* — or the slightly worse version, *we tuned it, it worked all day, and then someone redeployed*.

### 11.2 Filesystem realities on the roboRIO

All filesystem access goes through `design/06`'s `Platform` facade, never through `edu.wpi.first.wpilibj.Filesystem` directly (`DESIGN.md` §16 item 3; domain 06's `volatileApiIsConfined` ArchUnit rule).

| Path | Accessor | Persistence | Notes |
|---|---|---|---|
| `/home/lvuser/deploy` | `Platform.deployDir()` | Rewritten by `./gradlew deploy` | This is where `src/main/deploy/**` lands. **[UNVERIFIED]** whether GradleRIO deletes files not present in the source tree — behavior has varied by year and by artifact config — so Rootstock treats anything here as *replaceable at any deploy* and never writes runtime state to it. |
| `/home/lvuser` | `Platform.persistentDir()` | Survives deploys and reboots | Where runtime state belongs. Cleared only by re-imaging. |
| `/U` (USB stick) | — | Survives everything, removable | Used for logs, not for gains — a gains file that vanishes when someone borrows the stick is worse than no gains file. |
| `Preferences` (NT-backed roboRIO flash) | — | Survives deploys and reboots | Flat `String -> double` keys, no structure, no metadata, no diffing, and the same key namespace as every other subsystem. Rejected as the primary store; see §11.7. |

In desktop simulation, `Platform.persistentDir()` is the launch directory and `Platform.deployDir()` is `pwd/src/main/deploy` — both verified against the `Filesystem` behavior the facade wraps. So the same code paths work in sim with no branching, and a student tuning in sim writes a file they can actually see in their project.

### 11.3 Load-order precedence

Four sources, lowest to highest priority. Every value records which source it came from, and that source string is what appears in the NT metadata (§5.5) and the UI.

```
1. CODE_DEFAULT   the Gains passed to ControlConfig in RobotConfig.java
                  -> always present, always the fallback
                  -> Gains.UNTUNED is a legal CODE_DEFAULT and is what the template ships (D2c)

2. DEPLOY_FILE    src/main/deploy/rootstock/gains.json  ->  Platform.deployDir()/rootstock/gains.json
                  -> CHECKED INTO GIT. This is the team's committed, reviewed answer.
                  -> The template pre-creates this file (decision 2), so it always exists.

3. ROBOT_FILE     Platform.persistentDir()/rootstock/gains.json
                  -> written by the wizard / the Save button. Survives deploy and reboot.
                  -> the "we tuned it at the field on Saturday" file.

4. DASHBOARD      live NT value under /Tuning/<Mechanism>/<gain>
                  -> highest priority while tuning is enabled; ignored entirely under FMS
                     (FmsPolicy.tunablesLocked()).
```

Rules:

- Merging is **per value**, not per mechanism. If `gains.json` on the robot only contains `kP`, every other gain still comes from the deploy file or the code default. This matters because a student who bisects only kG should not accidentally revert kV.
- Loading happens once, in `TuningRegistry.register(target)`, before the first `gainSink().apply(...)`.
- A mechanism whose `configHash` (§7.6) differs from the one recorded in a file **ignores that file's values** and raises `Alerts.error("Tuning", ..., MatchImpact.BLOCKS_MATCH)`: `"Elevator gains in /home/lvuser/rootstock/gains.json were tuned for a different gear ratio (12.0, now 15.0). Ignoring them and using code defaults. Delete the file or re-tune."` `BLOCKS_MATCH`, not `PIT_ONLY`, because the code default the mechanism falls back to may be `Gains.UNTUNED`, and a mechanism holding `UNTUNED` refuses closed-loop control on hardware (D2c) — the robot will not move that mechanism at all. This is the single most valuable line in the whole persistence layer: gains silently surviving a mechanical change is how a robot gets destroyed after a rebuild.
- A malformed or unreadable file raises `Alerts.warning("Tuning", ..., MatchImpact.PIT_ONLY)` and is skipped. **A robot that will not boot because a JSON file has a stray comma is unacceptable** — the same rule design/01 §5.6 applies to config errors.
- Under FMS, the `DASHBOARD` tier is skipped entirely and the effective values are frozen at boot. The UI states which tier won, per value, so a pit crew can answer "what is the robot actually running?" in one glance.

### 11.4 File format

Plain JSON, hand-editable, diffable, with metadata that makes it self-explaining. Written with a deterministic key order so a git diff shows only what changed.

`src/main/deploy/rootstock/gains.json` (checked in, shipped pre-created by `RootstockTemplate`) and `Platform.persistentDir()/rootstock/gains.json` (runtime) share one schema.

> **Revision 4 — the `gains` block is exactly the seven doubles of `Gains` (D1a).** Everything revision 3 kept inside it that is not a gain — `iZone`, `iMaxVolts`, `tolerance`, and the whole `profile` object — moves to a sibling `control` block in **user units**, mirroring the `/Tuning` schema of §5.5 and `ControlConfig`'s ownership. `PersistenceSchemaTest` asserts the `gains` keys are exactly `GainId.values()` and the `control` keys are exactly the ten `ControlConfig` tunables.

```json
{
  "schema": "rootstock.gains/1",
  "writtenAt": "2026-08-08T14:12:33Z",
  "writtenBy": "TuningWizard 0.1.0",
  "wpilib": "2026.2.2",
  "mechanisms": {
    "Elevator": {
      "configHash": "e3b0c44298fc1c14",
      "archetype": "ELEVATOR",
      "siDomain": "LINEAR_METERS",
      "gains": {
        "kP": 128.00,
        "kI": 0.0,
        "kD": 4.93,
        "kS": 0.220,
        "kV": 5.000,
        "kA": 0.060,
        "kG": 0.2528
      },
      "control": {
        "iZone": 0.0,
        "iMaxVolts": 0.0,
        "maxVelocity": 1.60,
        "maxAcceleration": 6.00,
        "jerk": 0.0,
        "tolerance": 0.0127,
        "velocityTolerance": 0.05,
        "goalDebounceSeconds": 0.06,
        "manualDeadband": 0.10,
        "manualScale": 0.30
      },
      "provenance": {
        "kS": "WIZARD_OLS", "kV": "WIZARD_OLS", "kA": "WIZARD_OLS",
        "kG": "WIZARD_BISECTION", "kP": "WIZARD_REFINE", "kD": "WIZARD_LQR",
        "kI": "CODE_DEFAULT"
      },
      "quality": {
        "voltageFitR2": 0.981,
        "rmseVolts": 0.094,
        "samples": 4820,
        "finalResponse": "GOOD",
        "riseTimeSec": 0.31, "overshootPct": 1.4, "settleTimeSec": 0.47,
        "steadyStateErrorSi": 0.002
      },
      "preferredMode": "EXPRESS",
      "simPromotion": {
        "completedAt": "2026-08-08T13:41:02Z",
        "recipeVersion": "elevator/1",
        "runsCompleted": 9,
        "worstMarginToLimitSi": 0.061,
        "worstMarginToHardStopSi": 0.131,
        "worstCaseDescription": "kV x1.0, kA x3.0, position reference +5% of travel"
      },
      "setpoints": { "STOW": 0.0, "L1": 0.2032, "L2": 0.5207, "L3": 0.9525, "L4": 1.3335 },
      "session": { "mode": "TEACHING", "predictionsCorrect": 3, "predictionsAsked": 4 }
    }
  }
}
```

> The `simPromotion` numbers are self-consistent with §7.1's derived band: the supervisor's guard on this mechanism is 0.06985 m, so a run that stopped 0.061 m inside the band ended 0.061 + 0.070 = 0.131 m from the hard stop. `SimPromotionGateTest` asserts that identity rather than trusting two independently authored numbers.

`TunedValueStore`:

```java
package org.rootstock.tuning.persist;

import java.nio.file.Path;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;

public final class TunedValueStore {

  /** Platform.persistentDir()/rootstock/gains.json on the roboRIO; ./rootstock/gains.json in sim. */
  public static Path robotFile() { /* Platform.persistentDir() */ return null; }

  /** Platform.deployDir()/rootstock/gains.json; src/main/deploy/rootstock/gains.json in sim. */
  public static Path deployFile() { /* Platform.deployDir() */ return null; }

  /**
   * Resolve the effective values for a mechanism using the precedence in section 11.3.
   * Never throws: an unreadable or malformed file raises a PIT_ONLY warning and is skipped,
   * because a robot that will not boot because a JSON file has a stray comma is unacceptable.
   */
  public static Resolved resolve(String mechanism, String configHash, Gains codeDefault) { /* ... */ return null; }

  public record Resolved(Gains gains,
                         java.util.Map<String, Double> controlValues,   // the ten ControlConfig tunables
                         java.util.Map<GainId, String> provenance,
                         java.util.List<String> warnings) {}

  /** Atomically write the runtime file (temp file + rename), preserving other mechanisms' entries. */
  public static void saveToRobot(String mechanism, String configHash, Gains gains,
                                 java.util.Map<String, Double> controlValues,
                                 java.util.Map<GainId, String> provenance,
                                 Optional<QualityRecord> quality) { /* ... */ }

  /** Persist a named setpoint, keyed by RobotId so a practice-bot value can be promoted. */
  public static void saveSetpoint(String mechanism, String setpointName, double userUnits) { /* ... */ }

  /** Delete the runtime entry for one mechanism, reverting to the deploy file / code default. */
  public static void forget(String mechanism) { /* ... */ }
}
```

Atomic write is mandatory: write `gains.json.tmp`, `fsync`, then `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`. A brownout mid-write must not leave a truncated file that bricks the next boot.

### 11.5 Getting tuned values back into source

The runtime file is the safety net. The *committed* file is the goal, because it is the artifact that survives a student graduating — which the small-team research names as the single biggest institutional risk.

Three write-back paths, all one action:

**(a) Update the deploy file.** `ValueExporter.writeDeployBaseline()` writes `src/main/deploy/rootstock/gains.json` **when running in simulation** (where that path is inside the project) and, on the robot, writes `Platform.persistentDir()/rootstock/gains-for-commit.json` plus a console line telling the student to copy it. This is the primary path and it produces a git diff a mentor can review:

```
   "Elevator": {
     "gains": {
-      "kG": 0.0,
+      "kG": 0.2528,
-      "kP": NaN,
+      "kP": 128.00,
```

**(b) Paste-ready Java.** `ValueExporter.toJava(mechanism)` returns a block matching the user's stated conventions (`kConstantName` / `UPPER_SNAKE_CASE`, unit in a trailing comment, 4-space indent, no `m_` prefix on constants) so it drops straight into the one-file `RobotConfig.java` the template mandates. **It emits named-field `Gains` construction, never a seven-double constructor**, because D1a's whole point is that `reefscape2025/util/custom/GainConstants.java`'s positional-overload bug must be unrepresentable — including in generated code:

```java
// ---- Elevator ---- generated by Rootstock ValueExporter 0.1.0 on 2026-08-08T14:12:33Z
// Plant: 2x Kraken X60 FOC, 12:1, 22T #25 sprocket, 2-stage cascade, 24 lb, 55 in of travel.
// Fit: R2 0.981, RMSE 0.094 V, 4820 samples. Final response: GOOD (rise 0.31 s, 1.4% overshoot).
public static final Gains ELEVATOR_GAINS =
    Gains.pid(/* kP V/m */ 128.00, /* kI V/(m*s) */ 0.0, /* kD V/(m/s) */ 4.93)
         .withKs(0.220)      // V
         .withKv(5.000)      // V/(m/s)
         .withKa(0.060)      // V/(m/s^2)
         .withKg(0.2528);    // V   (constant gravity, elevator)

// These are ControlConfig, not Gains (D1a): policy, not measurement.
public static final double ELEVATOR_MAX_VELOCITY_MPS      = 1.60;   // 78% of the 2.040 m/s the
public static final double ELEVATOR_MAX_ACCELERATION_MPS2 = 6.00;   //   voltage budget allows
public static final double ELEVATOR_TOLERANCE_METERS      = 0.0127; // 0.5 in
```

The block is printed to the console, published to `/RootstockTuner/export/java` (string), and written to `Platform.persistentDir()/rootstock/Elevator-gains.java.txt`. A student with only a Driver Station can select it out of the console.

**(c) The tuning report.** `ValueExporter.markdownReport()` produces a full session record — every step, every measurement, every accepted and rejected value, every warning, with the final numbers. This is the artifact a student attaches to a pull request, and it is the closest thing to an answer for "the person who understood this graduated". It is reproduced in full in §11.5c.

#### 11.5c A complete worked session

Every number below is derived from the one plant this document uses throughout (§4.2, §6.5): 2× Kraken X60 FOC, `Reduction.ofStages(3.0, 4.0)` = 12:1, `LinearAxis.sprocket(Inches.of(0.25), 22, 2)` = 0.2794 m per drum rotation, 55 in = 1.397 m of travel, 24 lb of moving mass, tolerance 0.5 in = 0.0127 m.

```markdown
# Elevator tuning session - 2026-08-08 14:12
Recipe: elevator/1   |   Mode: TEACHING   |   Rootstock 0.1.0   |   WPILib 2026.2.2
Controller: port 2 (dedicated)   |   Test mode: yes   |   Idle mode: BRAKE (verified at pre-flight)
Sim promotion: PASSED 2026-08-08 13:41
  9/9 perturbed runs contained. Worst case kV x1.0, kA x3.0, position reference +5% of travel:
  stopped 0.061 m inside the band, 0.131 m from the hard stop.

Plant, as declared:
  2 x Kraken X60 FOC   12:1   0.2794 m per drum rotation   1.397 m of travel   10.886 kg
  Priors from the motor curve:  kV 5.309 V/(m/s)   kA 0.0258 V/(m/s^2)   kG 0.2533 V
                                free speed 2.260 m/s
Supervisor band: 0.0699 m .. 1.3272 m   (soft limits 0.0279 m .. 1.3691 m)

Predictions: 3 of 4 correct.
  MISSED - step 10 (refine): "Your kD is 4.93 and your kV is 5.00. What if kD were zero?"
           You said "it will overshoot and bounce a few times."
           It stopped cleanly. kV counts as damping before kD does anything: with kD = 0,
           zeta = 5.000 / (2 * sqrt(128.00 * 0.060)) = 1.14, still well above 0.7.

## 1. Pre-flight            PASS (2 warnings)
- Idle mode BRAKE (required for a gravity archetype -- section 7.3.1)
- Backlash 0.8 mm (fine, under 2 mm)
- Friction asymmetry 1.9x (WARN: 0.41 V up vs 0.22 V down. Check the carriage rollers.)
- Encoder slip after full sweep: 1.2 mm (fine)

## 2. Gravity pre-pass      kG = 0.2531 V
                            coast borrowed for 0.50 s: falls negative -> gSign +1, brake restored
                            bracket [0.0507, 0.4560] V from kGprior 0.2533 V
                            10 bisections, converged to +/- 0.0004 V, 4.9 s
                            0 probes aborted on the 3%-of-travel guard

## 3. Find kS               kS = 0.220 V    (0.226 up / 0.214 down, 6% asymmetry)
## 4. Find kV               kV = 5.11 V/(m/s)  provisional, ramp data only
## 5. Find kA               kA = 0.060 V/(m/s^2)
## 6. Confirm kG            kG = 0.2528 V   joint fit; pre-pass agreed to 0.1%
                            kV refined 5.11 -> 5.000   (0.94x the 5.309 prior -- OK)
                            kA 0.060 is 2.32x the 0.0258 prior -- inside the [0.25x, 6.0x] band
                            Fit quality: R2 0.981, RMSE 0.094 V, 4820 samples -> GOOD
## 7. Profile               EXPONENTIAL from kV/kA.
                            Voltage-achievable cruise = 0.85 x 12 / kV = 10.2 / 5.000 = 2.040 m/s.
                            Your declared 1.60 m/s is 78% of that -- fine.
                            At 1.60 m/s and 6.0 m/s^2 the peak demand is
                              kS + kG + kV*v + kA*a = 0.220 + 0.253 + 8.000 + 0.360 = 8.83 V
                            of your 12 V.  OK.
                            NOTE: the motor could deliver 39.4 m/s^2 at 80% of cruise. Your 6.0 is
                            a rigging choice, not a voltage limit, and that is the right way round.
## 8/9. LQR suggestion      kP 80.00 V/m, kD 4.93 V/(m/s)
                            (max error 0.050 m, max speed error 0.500 m/s, max effort 4.0 V, delay 0 ms)
                            wn 36.51 rad/s = 5.81 Hz,  zeta 2.27
                            WARN: kD (4.93) is large compared to kV (5.00) -- noisy-encoder risk.
                            WARN: zeta 2.27 is over-damped; expect SLUGGISH on the first attempt.
## 10. Refinement           step 0.349 m from 0.5169 m to 0.8661 m (both inside the band)
                            triangular profile, peak 1.0236 m/s, duration 0.3412 s
                            expected rise 0.273 s, expected settle 0.591 s
   iter 1: kP  80.00 kD 4.93 -> SLUGGISH   (rise 0.69 s vs expected 0.27 s)      -> kP x1.6
   iter 2: kP 128.00 kD 4.93 -> GOOD       (rise 0.31 s, 1.4% overshoot, settle 0.47 s, error 2 mm)
   ACCEPTED at iteration 2.  (cap was 8 x kP_lqr = 640 V/m; never approached.)
## 11. Full-travel verify   band sweep 0.0699 m -> 1.3272 m -> 0.0699 m
                            up:   rise 0.71 s, 2.4% overshoot, settle 1.02 s, error 2 mm
                            down: rise 0.69 s, 3.0% overshoot, settle 0.98 s, error 3 mm
                            Symmetric -> kG is correct.

## Final gains
kS 0.220 | kV 5.000 | kA 0.060 | kG 0.2528 | kP 128.00 | kI 0.0 | kD 4.93
```

> **Why this session's kP is not `design/01` §5.4's kP.** `design/01`'s reference block for the *same geometry* carries `kP 80.0, kD 2.0` — 8793's hand-tuned answer on the real carriage. This session's `128.00 / 4.93` is the wizard's answer at a 5 cm error preference on the *simulated* plant. Two different, both-defensible answers for one mechanism is exactly what §13.11 means by *"match the response shape, not the number"*, and it is why the practice-mode table there separates checkable feedforward answers from feedback preferences.

### 11.6 The Save flow

Nothing is persisted implicitly. When a recipe reaches `DONE` the wizard shows:

```
  Elevator is tuned.   kS 0.220  kV 5.000  kA 0.060  kG 0.2528  kP 128.00  kD 4.93

  A  Save to the robot        (survives reboot and redeploy)
  Y  Save + write commit file (also writes gains-for-commit.json and the Java block)
  B  Discard                  (revert to the gains this session started with)
```

Saving is also available at any time from the dashboard (`/RootstockTuner/cmd/save`) so a student who hand-tunes with the sliders and gets it right can keep the result without running a recipe at all. That path alone would have solved the whole problem for 8793.

### 11.7 Rejected alternatives

- **`Preferences` as the primary store.** It persists correctly and it is the documented WPILib answer, but it is a flat key-value namespace shared with everything else on the robot, it carries no provenance, no quality record and no `configHash`, and it is not diffable or reviewable. Its keys also appear in NetworkTables under a path we do not control. We provide `TunedValueStore.mirrorToPreferences(true)` as an opt-in for teams that already build tooling around `Preferences`, and nothing more.
- **Writing gains into `src/main/deploy` at runtime on the robot.** The deploy directory is the deploy task's territory; writing there invites a silent revert on the next deploy and, depending on GradleRIO's file-artifact configuration, possible deletion. Runtime state goes in `Platform.persistentDir()/rootstock/`.
- **Regenerating `RobotConfig.java` automatically.** Tempting, and wrong: it puts a robot program in the business of rewriting its own source, it fights the team's formatter, and it breaks the reviewability that makes the committed file valuable. We generate a *block to paste* and a *JSON file to commit*, and a human decides.

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
| **A custom NT-driven web UI served from the roboRIO** | Genuinely attractive: `edu.wpi.first.net.WebServer.start(int port, String path)` already serves static files, ports in the 5800-5810 range are conventionally open for team use ([UNVERIFIED] against the 2027 game manual — verify at kickoff), and it would let us reproduce WPILib's exact two-stacked-plot tutorial layout. But it needs an NT4 WebSocket client in JavaScript, it is a second UI to maintain, and if it breaks at an event the team has no fallback. **Not scheduled: it is in no milestone M1–M24, so it is outside v0.1.** If it is ever built it would be after v0.1, layered on the same NT schema so the primary path is unaffected. |
| **A desktop app** | Another install, another version to keep in sync, another thing that is the wrong version on the one laptop that matters. **Rejected.** |
| **SmartDashboard / Shuffleboard** | Deleted in 2027. **Rejected outright.** |

The decisive argument is the failure mode. If the shipped Elastic layout is missing, a student can drag four widgets onto a tab and be running in ninety seconds, because everything is a plain NT4 double or string. Every other option has a failure mode that ends with "we can't tune today."

### 12.2 Complete NT topic schema

Everything below is plain NT4. No structs, no protobuf, no AdvantageKit-specific types on the wire — the AdvantageKit dependency (§5.6) is how the values are *recorded*, not how they are *published*.

**Editable values** (§5.5): `/Tuning/<Mechanism>/...` — seven gain topics, ten `ControlConfig` topics, and the `Setpoints/` subtable.

**Wizard state and narration:**

| Topic | Type | R/W | Meaning |
|---|---|---|---|
| `/RootstockTuner/version` | string | R | `"Rootstock 0.1.0 / WPILib 2026.2.2"` |
| `/RootstockTuner/mechanisms` | string[] | R | Every registered mechanism name |
| `/RootstockTuner/selected` | string | RW | Currently selected mechanism |
| `/RootstockTuner/MechanismChooser` | (chooser) | RW | `SendableChooser<String>`-shaped topics so Elastic's ComboBox binds directly |
| `/RootstockTuner/state` | string | R | `IDLE`/`READY`/`PREFLIGHT`/`ARMED`/`RUNNING`/`REVIEW`/`ABORTED`/`DONE`/`DISABLED_REPLAY` |
| `/RootstockTuner/enableHeld` | boolean | R | Mirror of the trigger, so the student can see the robot agrees |
| `/RootstockTuner/step/index` | double | R | 1-based |
| `/RootstockTuner/step/count` | double | R | |
| `/RootstockTuner/step/title` | string | R | `"Step 3 of 11 - Find kS"` |
| `/RootstockTuner/step/explanation` | string | R | The lesson (§13) |
| `/RootstockTuner/step/willDo` | string | R | `"I will slowly increase voltage until the carriage starts to move."` |
| `/RootstockTuner/step/watchFor` | string | R | `"Watch for the exact moment it breaks loose."` |
| `/RootstockTuner/step/progress` | double | R | 0..1 |
| `/RootstockTuner/log` | string[] | R | Rolling narration, last 40 lines |
| `/RootstockTuner/mode` | string | R | `TEACHING` or `EXPRESS` (§8.12) |
| `/RootstockTuner/cmd/setMode` | string | RW | Student writes `TEACHING`/`EXPRESS`; consumed and cleared in the same loop |
| `/RootstockTuner/sharedControllerAck` | string | R | The verbatim `acknowledgeSharedController` reason, empty when the wizard has its own port |
| `/RootstockTuner/coastRiskAck` | string | R | The verbatim `acknowledgeCoastRisk` reason (§7.3.1), empty when idle mode is BRAKE |

**Formative assessment** (§8.4) — five topics, bound to an Elastic ComboBox and text displays. No new widget types:

| Topic | Type | R/W | Meaning |
|---|---|---|---|
| `/RootstockTuner/predict/question` | string | R | `"Your kD is 4.93 and your kV is 5.00. What if kD were zero?"` |
| `/RootstockTuner/predict/options` | string[] | R | Exactly three plain-language outcomes |
| `/RootstockTuner/predict/answer` | double | RW | Index 0/1/2, written by the ComboBox or by the D-pad |
| `/RootstockTuner/predict/score` | string | R | `"Predictions: 7 of 9"` |
| `/RootstockTuner/predict/feedback` | string | R | The branched `Coach` text, populated after the step runs |

**Live plot topics** (the two stacked plots, matching WPILib's tutorial layout):

| Topic | Type | Meaning |
|---|---|---|
| `/RootstockTuner/plot/setpoint` | double | Profile setpoint, SI |
| `/RootstockTuner/plot/measurement` | double | Measured position or velocity, SI |
| `/RootstockTuner/plot/goal` | double | Final goal (flat line), SI |
| `/RootstockTuner/plot/error` | double | setpoint - measurement |
| `/RootstockTuner/plot/volts` | double | Total commanded volts |
| `/RootstockTuner/plot/ffVolts` | double | Feedforward contribution (NaN when not observable) |
| `/RootstockTuner/plot/fbVolts` | double | Feedback contribution (NaN when not observable) |
| `/RootstockTuner/plot/velocity` | double | Measured velocity, SI |
| `/RootstockTuner/plot/amps` | double | Stator current, when available |

Publishing the feedforward and feedback contributions **separately** is deliberate and is one of the highest-value teaching artifacts in the whole design: a student who can see that the feedforward line carries 95% of the voltage and the feedback line only wobbles around zero has *understood* feedforward-before-feedback in a way no paragraph achieves.

**Result and diagnostics:**

| Topic | Type | Meaning |
|---|---|---|
| `/RootstockTuner/result/gain` | string | `"kS"` |
| `/RootstockTuner/result/value` | double | Suggested value |
| `/RootstockTuner/result/previous` | double | What it was |
| `/RootstockTuner/result/headline` | string | `"kS = 0.220 V"` |
| `/RootstockTuner/result/quality` | string | `"Fit R2 0.981, RMSE 0.094 V - good"` |
| `/RootstockTuner/result/verdict` | string | Plain-language diagnosis (§10.4) |
| `/RootstockTuner/result/recommendation` | string | Plain-language action |
| `/RootstockTuner/result/warnings` | string[] | |
| `/RootstockTuner/diagnostics/riseTimeSec` | double | |
| `/RootstockTuner/diagnostics/overshootPct` | double | |
| `/RootstockTuner/diagnostics/settleTimeSec` | double | |
| `/RootstockTuner/diagnostics/steadyStateErrorSi` | double | |
| `/RootstockTuner/diagnostics/residualVolts` | double | |
| `/RootstockTuner/diagnostics/dampingRatio` | double | |
| `/RootstockTuner/diagnostics/naturalFrequencyHz` | double | The `fn` of §9.2.2 |
| `/RootstockTuner/diagnostics/oscillationHz` | double | |
| `/RootstockTuner/diagnostics/classification` | string | `ResponseClass` name |
| `/RootstockTuner/diagnostics/saturated` | boolean | |

**Safety and identification:**

| Topic | Type | Meaning |
|---|---|---|
| `/RootstockTuner/safety/tripped` | boolean | |
| `/RootstockTuner/safety/reason` | string | `AbortReason` name (one of the twelve, §7.2) |
| `/RootstockTuner/safety/message` | string | The student-facing sentence from §7.2 — **and the `arm()` precondition message when an arm was refused (§7.3)** |
| `/RootstockTuner/safety/envelope` | string | JSON dump of the active `SafetyEnvelope` |
| `/RootstockTuner/sysid/r2` | double | `voltageFitR2` |
| `/RootstockTuner/sysid/rmseVolts` | double | |
| `/RootstockTuner/sysid/samples` | double | |
| `/RootstockTuner/lastSysIdLog` | string | Path to the plain WPILog written for SysId |

**Secondary (laptop-only) control** — momentary booleans consumed and reset in the same loop:

`/RootstockTuner/cmd/{start, accept, retry, back, skip, abort, save, saveAndExport, preflight}`

**Export:**

`/RootstockTuner/export/java` (string), `/RootstockTuner/export/json` (string), `/RootstockTuner/export/report` (string).

### 12.3 The shipped Elastic layout

`src/main/deploy/elastic-tuning-layout.json`, served by `WebServer.start(5800, Platform.deployDir().getPath())` exactly as the user's template already does for its driver layout — and, under decision 2, **shipped pre-created inside `RootstockTemplate`** rather than being something a team is told to generate. One tab, `RootstockTuner`, laid out in a 2-column grid:

| Widget | Type | Bound to |
|---|---|---|
| Mechanism | ComboBox Chooser | `/RootstockTuner/MechanismChooser` |
| State | Large Text Display | `/RootstockTuner/state` |
| Step | Large Text Display | `/RootstockTuner/step/title` |
| Progress | Number Bar (0-1) | `/RootstockTuner/step/progress` |
| **What this step teaches** | Large Text Display (tall) | `/RootstockTuner/step/explanation` |
| **What the robot will do** | Large Text Display | `/RootstockTuner/step/willDo` |
| Enable held | Boolean Box | `/RootstockTuner/enableHeld` |
| Safety | Boolean Box + Large Text Display | `/RootstockTuner/safety/tripped`, `/safety/message` |
| **Setpoint vs measured** | Graph (2 series) | `/RootstockTuner/plot/setpoint`, `/plot/measurement` |
| **Commanded volts (FF vs FB)** | Graph (3 series) | `/plot/volts`, `/plot/ffVolts`, `/plot/fbVolts` |
| Result | Large Text Display | `/RootstockTuner/result/headline` |
| Diagnosis | Large Text Display (tall) | `/RootstockTuner/result/verdict` |
| Recommendation | Large Text Display (tall) | `/RootstockTuner/result/recommendation` |
| **Predict: the question** | Large Text Display (tall) | `/RootstockTuner/predict/question` |
| **Predict: your answer** | ComboBox | `/RootstockTuner/predict/options` → `/RootstockTuner/predict/answer` |
| **Predict: how you did** | Large Text Display (tall) | `/RootstockTuner/predict/feedback` |
| Prediction score | Text Display | `/RootstockTuner/predict/score` |
| **Gains** | **7x** Text Display (editable) | `/Tuning/<Mechanism>/{kS,kV,kA,kG,kP,kI,kD}` |
| Integrator (collapsed by default) | 2x Text Display (editable) | `/Tuning/<Mechanism>/{iZone,iMaxVolts}` |
| Motion (collapsed by default) | 3x Text Display (editable) | `/Tuning/<Mechanism>/{maxVelocity,maxAcceleration,jerk}` |
| Tolerances (collapsed by default) | 3x Text Display (editable) | `/Tuning/<Mechanism>/{tolerance,velocityTolerance,goalDebounceSeconds}` |
| Max acceptable error | Number Slider | `/RootstockTuner/<Mechanism>/lqr/maxError` |
| Max control effort | Number Slider | `/RootstockTuner/<Mechanism>/lqr/maxVolts` |
| Mode | ComboBox | `/RootstockTuner/mode` → `/RootstockTuner/cmd/setMode` |
| Alerts | Alerts widget | `Alerts` group `Tuning` |

The **seven** gain widgets are exactly `GainId.values()`; revision 3's layout listed seven here while §5.5 declared twelve topics, which is one of the ways the two halves of that revision were visibly out of step.

The two stacked graphs are placed adjacently and sized identically **on purpose**: they reproduce the layout of WPILib's own browser tuning tutorials (`{prefix}_plotVals` above `{prefix}_plotVolts`). A student who learned the shape of a good response in the browser sees literally the same shape on their elevator. That continuity is worth more than any feature in this section.

Because the gain widgets bind to `/Tuning/<Mechanism>/...` and the mechanism name is part of the path, the layout is generated per-mechanism at deploy time by `ElasticLayoutGenerator.write(Path)` from the registry, rather than hand-authored. A team adding a mechanism regenerates rather than dragging widgets.

### 12.4 AdvantageScope, for free

No work required. Because values live at `/Tuning/<Mechanism>/<key>` as plain NT4 doubles, AdvantageScope's tuning mode (slider icon right of the search bar; purple when active) shows them under the Tuning table and edits them live, and all the `/RootstockTuner/plot/*` topics graph directly. Teams that prefer AdvantageScope get the full experience minus the wizard narration, with no extra configuration. *(Revision 2 added "and no AdvantageKit dependency" here; that clause is **withdrawn** — every Rootstock team has an AdvantageKit dependency now.)*

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

This is shipped content, not a placeholder. It lives in `org.rootstock.tuning.wizard.Lessons` as `public static final String` constants, is published to `/RootstockTuner/step/explanation`, and is rendered verbatim in the docs site so the docs and the robot can never disagree. Every string below is the actual text.

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
> *In this step, Rootstock will slowly increase the voltage from zero until it sees the mechanism move, in both directions, and average the two answers.*

### 13.2 `Lessons.KV`

> **kV — the price of speed**
>
> A spinning motor pushes back. The faster it spins, the more it fights you — that is the same effect that makes a motor work as a generator. So to hold a steady speed, you have to keep paying voltage the whole time.
>
> kV is the price. It is measured in volts per unit of speed: volts per meter-per-second for something that slides, volts per radian-per-second for something that turns. Want to go twice as fast? Pay twice as much.
>
> kV is the single most important number in this whole process. Get kV right and your mechanism almost controls itself — the controller can predict, before it even starts moving, exactly how much voltage this move is going to need.
>
> **If kV is too small:** the mechanism always runs slower than you asked, and the feedback term has to keep making up the difference.
> **If kV is too big:** it overshoots your commanded speed and the feedback has to fight it back down.
>
> *In this step, Rootstock will ramp the voltage up very slowly and watch how fast the mechanism goes at each voltage. Slowly, on purpose: if it ramped quickly, some of the voltage would be going into speeding up rather than into holding speed, and we would not be able to tell the two apart.*

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
> *Do not be alarmed by this step. It will look and sound more violent than the others. Rootstock has worked out a step size that uses less than half your remaining travel and will stop it after a second and a half.*

### 13.4 `Lessons.KG`

> **kG — the holding tax**
>
> Gravity never turns off. Stop pushing on an elevator carriage and it falls. Stop pushing on an arm and it swings down.
>
> kG is exactly how many volts it takes to hold still against gravity and do nothing else. It is measured in volts.
>
> On an elevator, kG is the same everywhere: the carriage weighs the same at the bottom and at the top. On an arm, it depends on the angle. It is biggest when the arm sticks straight out sideways, and it drops to zero when the arm points straight up or straight down — which is why the maths multiplies kG by the cosine of the angle. That is also why your arm's zero angle has to be *horizontal*: if the code thinks sideways is somewhere else, the cosine is wrong at every single angle.
>
> **If kG is too small:** the mechanism settles a little low, every time, and the controller sits there holding a steady voltage trying to lift it.
> **If kG is too big:** it settles a little high, or creeps upward when you leave it alone.
> **If kG is right but the arm's zero is wrong:** it droops on one side of its travel and creeps up on the other. That asymmetry is the fingerprint.
>
> *WPILib's own arm guide says you have to get kG right to about four decimal places. That is why Rootstock does not ask you to guess and redeploy. In this step it will first let go for half a second to see which way your mechanism falls, then hold it and narrow in on the exact holding voltage by cutting the range in half ten times — landing within about one part in six hundred of the range your mechanism's own mass and gearing predict, in five seconds. It never lets the mechanism drift more than a thirtieth of its travel while it does this.*
>
> *Ten halvings, not more, on purpose. The range it starts from comes from your mechanism's own mass and gearing, so it is already close, and going further would be measuring a number more precisely than the encoder can actually see it — while giving the mechanism more chances to run into something.*
>
> *If your mechanism is in brake mode, or its gearbox is stiff enough that it does not move when released, Rootstock works out which way gravity pulls a different way: it measures how hard the mechanism is to break loose in each direction. The harder direction is the one gravity is fighting. It will tell you when it does this.*

> **Revision 4 — the numeric claim in §13.4 changed, and `LessonsNumericClaimTest` is why.** Revisions 2 and 3 said *"about two thousandths of a volt."* That figure was computed for this document's **arm** (`kGprior ≈ 1.2 V`, bracket width 1.92 V, `1.92 / 2^10 = 0.00188 V`) and is simply wrong for the **elevator** (`kGprior = 0.253323 V`, width 0.405317 V, `0.405317 / 2^10 = 0.000396 V`). A lesson string cannot carry a number that depends on the mechanism. The claim is now *structural* — `1.6 / 2^10 = 1/640`, "about one part in six hundred" — which is true for every mechanism and is exactly what the code computes. This is the same class of failure `LessonsNumericClaimTest` was created to catch when `ITERATIONS` went from 18 to 10 and three places still said "eighteen."

### 13.5 `Lessons.P`

> **P — the software spring**
>
> Everything up to now has been the controller *predicting* what voltage a move needs. P is the first term that *reacts* to what actually happened.
>
> P is a spring made of software. The further you are from where you want to be, the harder it pulls you back. Twice the error, twice the push. That is all it does.
>
> Its unit is volts per unit of error — volts per meter for an elevator, volts per radian for an arm. That is also why a kP that is right for an elevator looks nothing like a kP that is right for a turret, and why a kP copied off the internet is almost never right for your robot.
>
> **If kP is too small:** the mechanism is sluggish. It gets there eventually, or stops slightly short and stays there.
> **If kP is too big:** it overshoots and bounces, exactly like a spring that is too stiff. Turn it up further and the bouncing never stops. Turn it up further still and the bouncing gets *bigger* each time, and that is how mechanisms break.
>
> *Rootstock does not ask you to guess kP. It already measured kV and kA, which together describe how your mechanism responds to voltage — so it can calculate a starting kP from two questions that actually mean something: how much error can you live with, and how many volts are you willing to spend fixing it. Smaller error, or more volts, gives a bigger kP. In fact, for a position mechanism those two numbers are all kP is: volts divided by error.*

### 13.5a `Lessons.WHAT_THE_SLIDERS_DO`

Shown in the `LqrSuggestStep` panel, directly under the two sliders (§9.2.2). It exists because the kP step is the one place the wizard was in danger of handing over a number from an oracle, and a number with no story behind it teaches nothing.

> **The two sliders — what you are actually choosing**
>
> Rootstock is not guessing your kP. It already measured how your mechanism responds to voltage, so there is a real answer — but the answer depends on what *you* want, and these two sliders are how you say it.
>
> **"How much error can I live with"** is how fussy you are. Tell it a millimeter and it will fight hard for that millimeter. Tell it a centimeter and it will relax.
>
> **"How many volts may I spend"** is how much muscle it is allowed to use getting there. More volts, more push.
>
> Tighten the error or raise the volts, and kP goes up. Both knobs push the same way, and that is not a coincidence: caring more and being allowed to push harder are the same instruction to a controller. For a position mechanism the relationship is exactly as simple as it sounds — kP is the volts you allowed, divided by the error you allowed.
>
> Now watch the two numbers above the gains, because they are what kP actually *means*:
>
> **The bounce rate** is how fast this mechanism would wobble if you knocked it off target. It goes up with the square root of kP — so tripling kP does not make your elevator three times faster, it makes it about 1.7 times faster.
>
> **The damping** is how quickly that wobble dies away. Above about 0.7 you will not see it bounce at all. Below 0.7 you will. Here is the part that catches everyone: raising kP makes the bounce faster *and* the damping worse, at the same time, from the same slider. That is why turning kP up forever does not work, and why the next number you tune is kD.
>
> *Your mechanism's own kV counts as damping too, for free, before kD does anything. That is why a shooter wheel usually needs no kD at all — and why some elevators do not either. If the damping number above is already comfortably over 0.7 with kD near zero, that is your kV doing the work.*

### 13.6 `Lessons.D`

> **D — the shock absorber**
>
> D is the damper that goes with P's spring. Think of the arm on a door that stops it slamming.
>
> D does not care where you are. It cares how fast the error is shrinking. If you are rushing at the target too fast, D pushes back and slows you down so that you *arrive* instead of crashing through. That is why P and D go together: P gets you there, D stops the bounce.
>
> **If kD is too small:** you get the overshoot-and-ring behavior from the P lesson.
> **If kD is too big:** the mechanism gets jittery and buzzy, often at a frequency far too fast for the mechanism to actually be moving that quickly. That is D amplifying the noise in your encoder reading and feeding it straight back into the motor.
>
> *If Rootstock tells you it is halving kD because it saw a 14 Hz buzz, that is what happened.*

### 13.7 `Lessons.I` — and why we make it hard to use

> **I — the grudge, and why you almost certainly do not want it**
>
> I keeps a running total of every bit of error you have ever had, and pushes harder the longer you have been wrong. It sounds like exactly what you want when your mechanism stops just short of its target. It is almost always the wrong tool in FRC, and WPILib says so directly: *integral gain is generally not recommended for FRC use.*
>
> Here is why. If your mechanism stops short and stays there, something is pushing against it that nothing in your feedforward is pushing back on. Ninety-nine times out of a hundred that something is friction (fix: raise kS) or gravity (fix: raise kG). Adding I does not remove the force — it just piles up error until it produces enough voltage to cancel it, every single time, from scratch.
>
> And it has a nasty failure mode called windup. While your mechanism is blocked — jammed, or at a hard stop, or waiting for something else to move out of the way — the total keeps growing. When it is finally free, all of that stored-up push comes out at once. That is how arms slam.
>
> Rootstock will let you use I. It just will not let you use it carelessly: you have to supply an I-zone (the error band outside which the integrator is switched off) and a voltage cap, in the same breath as kI. There is no plain `withI(kI)`, and there never was — the three numbers go together, on `ControlConfig`, in one call.
>
> *Before you reach for I, look at the number Rootstock shows you called "residual volts." That is exactly how much voltage the feedback term is holding, right now, to keep the mechanism where it is. That voltage is the feedforward term you are missing. Add it to kS or kG instead.*

### 13.8 `Lessons.MOTION_PROFILES`

> **Motion profiles — a plan instead of a wish**
>
> A setpoint is a wish. If your elevator is at the bottom and you tell it "be at 1.4 meters," you have asked it to teleport. The error is instantly 1.4 meters, so the P term instantly asks for a hundred volts you do not have, the motor saturates, and every gain you tuned stops mattering because the controller is just holding the throttle wide open.
>
> A motion profile is a plan. Instead of one impossible target, it hands the controller a new, *reachable* target every twenty milliseconds: a smooth path from where you are to where you want to be, with a speed limit and an acceleration limit that your mechanism can actually meet.
>
> Two things change once you have one. First, the error is never large, so P and D never saturate. Second — and this is the important one — the profile knows what speed you *should* be going at this exact instant, which is precisely what kV and kA need in order to predict the voltage. The feedforward can now do almost all of the work, and P and D are left cleaning up a small difference.
>
> That is the whole reason we tune feedforward first. With a good profile and good kS, kV, kA and kG, your P gain has very little left to do — and a P gain with very little to do is a P gain that cannot shake your robot apart.
>
> **You do not have to guess the speed limit.** That is the single most common place students put in a physically impossible number. Rootstock works out the fastest speed your voltage can actually sustain, straight from the kV it just measured, using WPILib's `ExponentialProfile.Constraints.fromCharacteristics(...)` — so the limit is, by construction, one your mechanism can do. The *acceleration* limit is usually not a voltage question at all: on a well-geared elevator the motor can accelerate far harder than the rigging should be asked to. Rootstock will tell you what the motor could do, and how many volts your chosen number actually costs, and then let you choose.

### 13.9 `Lessons.WHY_FEEDFORWARD_FIRST`

> **Why we do it in this order**
>
> Feedforward is the controller's *prediction*: given where you want to go and how fast, here is the voltage that should do it. Feedback is the controller's *correction*: here is a bit extra, because reality did not match the prediction.
>
> If you tune feedback first, you are asking the correction to do the prediction's job. It can — a big enough kP will drag almost anything to almost anywhere — but it does it by being wrong first and then reacting, which is exactly what overshoot and ringing are. And the bigger you make kP to compensate, the closer you get to the point where the mechanism shakes itself apart.
>
> So: kS, then kV, then kA, then kG, then kP, then kD. Prediction first, correction second. Every time.
>
> *(One wrinkle for elevators and arms: we measure gravity before we measure friction. You cannot see friction break loose while the mechanism is falling. So Rootstock does a quick gravity pass first, uses it to cancel gravity during the friction and speed measurements, and then re-solves gravity properly at the end using all the data at once. The order you learn the numbers in is still the order above.)*

### 13.10 `Lessons.WHY_UNITS_MATTER`

> **Why your kP is not the same as somebody else's kP**
>
> This trips up everyone, so it is worth thirty seconds.
>
> The number "kP = 50" means nothing on its own. It means volts per *something*, and every system measures that something differently:
>
> - Rootstock and WPILib measure error in meters or radians, and output in volts.
> - A Kraken running its own loop measures error in *motor-shaft rotations*, and its output might be volts, or a duty cycle, or amps, depending on which kind of request you send it.
> - A SPARK MAX measures error in rotations and outputs a duty cycle from -1 to 1.
>
> That is why the published starting kP for the *same swerve steer motor* is 0.01 on a SPARK MAX and 50 on a TalonFX. Same mechanism, same behavior, numbers five thousand times apart.
>
> Rootstock fixes this by having exactly one unit system — volts per SI unit — and converting once, at the boundary, inside the code that talks to your motor controller. Every number you see, save, and paste into `RobotConfig.java` is in those units. The number you learn from the WPILib arm tutorial transfers unchanged to your Kraken and to your NEO.
>
> *You can see the exact conversion Rootstock is doing for your mechanism in the "Conversion" line on the dashboard, and in the log at boot. It shows you the one number the whole conversion hangs on: how far your mechanism moves in one rotation.*

### 13.11 `Lessons.PRACTICE_MODE`

> **Learn this without a robot**
>
> Everything in this wizard runs in simulation. Type `./gradlew simulateJava`, pick a mechanism, and tune it exactly the same way, with the same plots and the same steps — except that a simulated elevator does not have a real ceiling to hit and a simulated arm does not have real fingers near it.
>
> WPILib publishes reference answers for its own simulated mechanisms, and Rootstock ships those same plants as practice targets. **Two different kinds of number live in that table, and it matters which is which:**
>
> **Feedforward gains are checkable answers.** kS, kV, kA and kG are properties of the *mechanism*. There is one right answer and your fit should land on it, inside the bands `ElevatorRecipeSimTest` uses:
>
> | Practice mechanism | kG | kV | kA | Your fit should land within |
> |---|---|---|---|---|
> | Flywheel | — | 0.0075 | — | kV ±15% |
> | Turret | — | 0.15 | — | kV ±15% |
> | Vertical arm | 1.75 | 1.95 | — | kG ±5%, kV ±15% |
> | Elevator | 2.28 | 3.07 | 0.41 | kG ±5%, kV ±15%, kA ±30% |
>
> **Feedback gains are choices, not answers.** WPILib's tutorials publish kP and kD too — flywheel 0.1; turret 0.3 / 0.05; arm 5 / 1; elevator 2.0 — and those are *their hand-tuned numbers at their preferences*. Your LQR result depends on the two sliders you moved, and it will usually be different. That is not the wizard being broken; it is the wizard answering the question you actually asked. **Match the response shape, not the number:** a clean arrival with a small overshoot and no ringing is right, whether kP came out at 2 or at 20. If you want to compare directly, set the error slider to the tutorial's tolerance and the volts slider to 4 V and see how close you land.
>
> Tune them until you can predict what the plot is going to do before it does it. Then turn the noise on (`practice.withNoise(true)`) and do it again — a real encoder is never as clean as a simulated one.
>
> **The wizard keeps score.** Before each of the important steps it asks you what you think is going to happen, in plain language, three options, no trick answers. It is not a test and getting it wrong blocks nothing — but at the end it tells you how many you called correctly, and so does the report you attach to your pull request. That number is the actual point of this whole practice mode. Aim for all of them on the same mechanism twice in a row before you touch the real robot.
>
> Once you can do that, tuning the real robot takes about eight minutes the first time and ninety seconds every time after that.

> **Revision 4 — why the table was split.** Revisions 1 through 3 headed a single column *"The answer you are looking for"* and put WPILib's hand-tuned kP values in it. The wizard's own LQR flow legitimately produces materially different kP on the same plant — §11.5c's worked report accepts 128.0 V/m on an elevator whose reference kP is 2.0, and §9.2.0 shows exactly why (kP is `uMax/eMax`, and the student picked those). A student who gets 80 from the panel and reads that "the answer" is 2.0 concludes the wizard is broken. That is the same trust failure §5.7 and §18 q4 worry about for SysId cross-checks, and it was self-inflicted.

### 13.12 How the lessons are validated

Documentation rot is a fast abandonment trigger, and an AI-written feedforward page that said *sin* where it should have said *cos* cost one competing library a reviewer's trust in a single forum post. So:

- Every lesson string is referenced by a JUnit test that asserts it is non-empty, under 1800 characters, and contains none of the forbidden jargon tokens (`Laplace`, `pole`, `eigen`, `transfer function`, `s-domain`, `Nyquist`, unescaped Greek letters). The limit was 1400 and moved to 1800 when `WHAT_THE_SLIDERS_DO` was added; it is a budget, not a physical law, and a lesson that needs more words than that is a lesson that needs splitting.
- Every *numeric claim* in a lesson is asserted by a test against the code that produces it, so a constant cannot change without the prose failing the build. The current set:

  | Claim | Asserted against |
  |---|---|
  | The four feedforward reference rows in §13.11 | The shipped practice plants |
  | "cutting the range in half **ten** times — landing within about **one part in six hundred** of the range" (§13.4) | `HoldBisectionStep.ITERATIONS`, and the identity `1.6 / 2^ITERATIONS == 1/640` where `1.6` is the `[0.2x, 1.8x] * kGprior` bracket width |
  | "in **five seconds**" (§13.4) | `ITERATIONS * (PROBE_SECONDS + typical recentre)`, with `PROBE_SECONDS` and `SETTLE_SECONDS` |
  | "never lets the mechanism drift more than a **thirtieth** of its travel" (§13.4) | The `0.03 * range` in-window guard |
  | "it measures how hard the mechanism is to break loose in each direction" (§13.4) | The existence of the `SIGN_BREAKAWAY` phase and the `(kS+kG) - abs(kS-kG) == 2*min(kS,kG)` identity |
  | "bounce rate goes up with the **square root** of kP — tripling kP makes it about **1.7 times** faster" (§13.5a) | `wn = sqrt(kP/kA)`; `sqrt(3) = 1.732` |
  | "raising kP makes the bounce faster *and* the damping worse" (§13.5a) | `zeta = (kD + kV) / (2*sqrt(kP*kA))`, monotone decreasing in kP |
  | "above about **0.7** you will not see it bounce" (§13.5a, §9.2.2, `PredictStep`) | The 0.7 threshold in `PredictStep`'s answer key and the `OVERSHOOT_RING` classifier |
  | "kP is the volts you allowed, divided by the error you allowed" (§13.5, §13.5a) | §9.2.0's `kP = uMax/eMax` identity, against the **continuous** `LqrDesign` solver only — see the `[UNVERIFIED]` note there |
  | The `ln(9) * tau` rise-time claim (§10.2) | `StepResponseAnalyzer.expectedRise` |

  A single `LessonsNumericClaimTest` owns this table. When `HoldBisectionStep.ITERATIONS` went from 18 to 10, this test is what was supposed to catch the three places the prose still said "eighteen." It exists because it did not — and revision 4 added the §13.4 row above after the same failure recurred with "two thousandths of a volt", a figure that was only ever true for one of the two mechanisms this document works through.
- The docs site renders these constants directly from the source file. There is no second copy of this text.
- The physics statements (cosine for arms, constant for elevators, kV as back-EMF, kA as inertia) are each cross-checked against the WPILib feedforward documentation in a review checklist that is part of the release process, not part of anyone's memory.

---

## 14. End-to-end example

Everything below is the *complete* code a team writes. This is a real elevator on two Krakens with the loop running on the motor controller — the same mechanism `design/01` §5.4 declares, so the two documents' examples are the same robot.

> **Revision 4 — this section was written against an API that does not exist.** `[SUPERSEDED-NAME]` Revisions 1 through 3 showed `LinearMechanism.builder("Elevator").talonFX(15, "rio").gearing(45.0).drumCircumference(...).stages(2).mass(...)`. `LinearMechanism` appears in **no** Rootstock document: `design/01`'s mechanism package is `PositionMechanism` / `VelocityMechanism` / `SimpleMechanism`, its config type is `PositionConfig`, and its geometry is `Reduction` + `LinearAxis`. The old example also seeded `Gains.zero()` as the taught placeholder, which **D2c** replaces with `Gains.UNTUNED`, and resurrected the 45:1 elevator `design/01`'s own revision-2 changelog fixed to 12:1 (45:1 gives 0.62 m/s of free speed at the carriage, against a `maxVelocity` of 2.94 m/s the same example asked for — 4.7× the free speed, physically impossible). A wizard-first reader — the most likely adoption path for exactly the teams this library targets — learned a config language that differed from the README's and `DESIGN.md`'s in builder, type names, geometry and the placeholder contract. The example below is `design/01` §5.4's, unmodified except for the two tuning-facing lines.

### 14.1 The mechanism (written once)

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import org.rootstock.config.*;          // PositionConfig, MotorSpec, HomingStrategy, Setpoint, ...
import org.rootstock.control.Gains;     // Gains is in control, NOT config
import org.rootstock.control.NeutralMode;
import org.rootstock.pure.units.Reduction;   // Reduction is in pure.units, NOT units
import org.rootstock.units.LinearAxis;

public final class RobotConfig {

  public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
      // --- what drives it ---------------------------------------------------
      .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                        .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
      // --- the gearbox: ONE place. Change this number and gains, sim, soft limits,
      //     Motion Magic constraints and telemetry units ALL follow. --------
      .reduction(Reduction.ofStages(3.0, 4.0))            // 12:1 -> 2.26 m/s free at the carriage
      // --- the geometry: 22-tooth #25 sprocket, 2-stage cascade -------------
      .axis(LinearAxis.sprocket(Inches.of(0.25), 22, /* cascade stages */ 2))   // 0.2794 m per drum rot
      // --- what measures it -------------------------------------------------
      .feedback(new FeedbackSpec.RotorOnly())
      // --- limits -----------------------------------------------------------
      .softLimits(Inches.of(0.0), Inches.of(55.0))
      .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
      // --- control ----------------------------------------------------------
      // ControlLocation is OMITTED on purpose: it defaults to ON_MOTOR_PROFILED because the
      // leader is a TalonFX, and describe() prints that with its provenance (D5).
      //
      // GAINS ARE Gains.UNTUNED, on purpose (D2c). Nobody types a guessed gain here.
      // In SIMULATION this resolves to a physics-derived first guess from the PlantPrior below and
      // the mechanism moves; on REAL HARDWARE the mechanism refuses closed-loop control and raises
      // "Elevator has never been tuned. Run the tuning wizard." Manual control and homing still work.
      // The wizard fills these in, and TunedValueStore loads them from
      // src/main/deploy/rootstock/gains.json on the very next boot (section 11.3).
      .gains(Gains.UNTUNED)
      .constraints(MotionConstraints.of(/* m/s */ 1.6, /* m/s^2 */ 6.0))
      .tolerance(Inches.of(0.5), /* velocity, m/s */ 0.05, /* debounce s */ 0.06)
      .neutralMode(NeutralMode.BRAKE)     // REQUIRED for a gravity archetype before the tuner will
                                          // arm it -- see section 7.3.1. It is also what you want.
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
}
```

```java
package frc.robot;

import org.rootstock.mechanism.PositionMechanism;

public final class Mechanisms {
  public static final PositionMechanism ELEVATOR = new PositionMechanism(RobotConfig.ELEVATOR);
}
```

**There is no tuning-specific line in either file.** `PositionMechanism implements TuningTarget` (`DESIGN.md` §7 package tree), and `RootstockRegistry.addAll(...)` routes it into `TuningRegistry` because of **D27**'s single-call registration — `instanceof TuningTarget → TuningRegistry`. Revisions 1 through 3 showed a hand-written `TuningRegistry.register(m_mechanism.tuningTarget())` call in the subsystem constructor; **D27 removed `registerAll` from the public API entirely** and replaced it with opt-*out* filters (`.excludeFrom(Registry.TUNING)`), so the register call is not something a team writes.

**Where the tuning types come from, for this config:**

| Tuning type | Derived from | Value here |
|---|---|---|
| `siDomain()` | `LinearAxis` | `LINEAR_METERS` |
| `controlLocation()` | leader `MotorSpec` (defaulted, D5) | `ON_MOTOR_PROFILED`, source `DEFAULTED` |
| `gravityMode()` | `LinearAxis.gravity()` | `CONSTANT` |
| `horizontalReferenceSi()` | `LinearAxis.horizontalReference()` | `0.0` |
| `toleranceSi()` | `ControlConfig.tolerance` through `MechanismUnits` | `0.0127 m` |
| `travelLimits()` | `PositionLimits` + the derived tuning margin | `min 0.0, max 1.397, softMargin 0.02794` |
| `plantPrior()` | `MotorGroup` + `Reduction` + `LinearAxis` + `SimConfig` | `PlantPrior.elevator(DCMotor.getKrakenX60Foc(2), Reduction.ofStages(3,4), 10.886, 0.0444679)` |
| `positionReference()` | `HomingStrategy.currentSpike()` | `HomedAgainstSwitch(() -> mechanism.isHomed())` |

**The tuning soft margin, and where it comes from.** `TravelLimits.softMargin` is **derived**, not typed: `max(0.02 * range, 2 * toleranceSi)`. On this elevator that is `max(0.02 × 1.397, 2 × 0.0127) = max(0.027940, 0.025400) = 0.027940 m`. Teams that want more room call `PositionConfig.Builder.tuningMargin(Measure<?>)`. **Cross-doc action (§19):** `design/01` must add that optional builder method and the derivation.

**And what happens if the derived margin is illegal.** Nothing throws. `TravelLimits.validate(owner)` returns a FATAL `ConfigError`, `TuningRegistry.register` hands it to `RootstockRegistry.addAll`, `Validation.printAll` prints it next to every other config error in the robot, and the robot **boots into SAFE_MODE** with a sentence on the driver station:

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Elevator"

  field    travelLimits.softMargin
  value    0.019
  expected >= 0.02794 (2% of 1.397 of travel)

  The tuning supervisor's abort band has to fit strictly inside your soft limits, so that it stops
  the mechanism before the device clamps silently. A margin this small leaves it no room, and you
  would get a supervisor that only appears to protect you.

  declared at frc.robot.RobotConfig.<clinit>(RobotConfig.java:41)

  The robot has booted into SAFE_MODE so you can read this message on the driver station.
```

Revisions 1 through 3 threw an `IllegalArgumentException` from `TravelLimits`' compact constructor for exactly this case, and §14.1's own prose bragged that *"the mechanism builder surfaces the same throw at `build()` time."* Since every example in both documents declares configs as `public static final`, that throw is an `ExceptionInInitializerError` from `<clinit>`: robot code never starts, the driver station shows red "Robot Code", and the carefully written message is a nested cause under JVM class-init frames. `DESIGN.md` §14 row 7 names that failure and declares it structurally unrepresentable; `design/01` §5.6 rebuilt an entire validation pipeline to prevent it. This document had reintroduced it, in the domain aimed at the least experienced users, and §16.3's "every example must construct legally" rule was written to police a symptom rather than the cause.

### 14.2 Robot wiring (written once, for the whole robot)

**The recommended shape — `RootstockLifecycle`, one registration list (D27), no hand-written periodics:**

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.littletonrobotics.junction.LoggedRobot;
import org.rootstock.core.RootstockLifecycle;
import org.rootstock.core.RootstockRegistry;
import org.rootstock.core.spi.LogConfig;   // core.spi, NOT telemetry -- ArchUnit rule 9 (2026-08-08).
                                            // `design/04` section 2.2b is still the sole DEFINITION site and
                                            // telemetry still owns every field's meaning; only the
                                            // package moved, because RootstockLifecycle.create(LogConfig)
                                            // is a core signature and rule 9 forbids an arrow out of
                                            // core. Tier and RobotMode moved with it, for the same
                                            // reason one level down. See `design/01` section 1.1a.
import org.rootstock.tuning.wizard.TuningWizard;

public class Robot extends LoggedRobot {

  private final RootstockLifecycle m_rootstock = RootstockLifecycle.create(LogConfig.defaults());

  private final CommandXboxController m_driver   = new CommandXboxController(0);
  private final CommandXboxController m_operator = new CommandXboxController(1);

  /**
   * Port 2, on purpose. The wizard's right trigger authorizes raw voltage to an arm; the driver's
   * right trigger shoots. Those must not be the same physical control. See section 7.4.2.
   */
  private final CommandXboxController m_tuningController = new CommandXboxController(2);

  private final TuningWizard m_tuner = TuningWizard.using(m_tuningController);

  public Robot() {
    // ONE list. RootstockRegistry inspects each argument once and routes it (D27):
    //   instanceof TuningTarget  -> TuningRegistry   (every PositionMechanism is one)
    //   instanceof HealthSource  -> HealthMonitor
    //   instanceof TelemetrySource -> telemetry
    //   instanceof SelfTestable  -> SelfTest
    // The wizard registers as a LifecycleHook. Nothing here calls periodic() by hand.
    // One entry per mechanism this robot has. Section 14.1 declares one; a real robot lists
    // all of them here, plus the wizard.
    RootstockRegistry.addAll(Mechanisms.ELEVATOR, m_tuner);
  }

  @Override public void robotPeriodic() {
    m_rootstock.beforeUserPeriodic();     // priority 30: TuningRegistry.drainPoller() -- ONE readQueue()
    CommandScheduler.getInstance().run();
    m_rootstock.afterUserPeriodic();
  }

  @Override public void disabledInit() { m_rootstock.disabledInit(); }
}
```

**That is the entire integration.** Two lines of tuning-specific code across the whole robot: one `TuningWizard.using(...)` field, and the wizard's name in the one registration list. Every mechanism is already a `TuningTarget`.

> **Revision 4 — why this replaced a hand-written `robotPeriodic()`.** Revisions 1 through 3 showed
> ```java
> public void robotPeriodic() {
>   TuningRegistry.periodic();
>   m_tuner.periodic();
> }
> ```
> which is a *third* documented minimal integration for the same adoption row: `DESIGN.md` §11c's "Tunables only" and "The tuning wizard" rows both list `RootstockLifecycle.create(...)` as the minimum code, and `DESIGN.md` §16 item 3 requires `TuningRegistry.periodic()` to stop owning its own rate gate. Two different minimum integrations for one row, only one of which matched the lifecycle-hook architecture, is exactly the drift the adoption matrix exists to prevent.

**The standalone, no-lifecycle mode**, for a team that wants the tuning system and nothing else — this is a **documented row in the §11c adoption matrix**, not an undocumented divergence, and `IncrementalAdoptionTest` compiles it:

```java
// Standalone mode: no RootstockLifecycle, no RootstockRegistry, no health monitors, no MatchContext.
// You give up the platform layer; you keep tunables, the wizard, and gains.json.
// You are then responsible for the two calls the lifecycle would have made for you, IN THIS ORDER,
// BEFORE your subsystem periodics:
public void robotPeriodic() {
  TuningRegistry.drainPoller();   // priority-30 equivalent: ONE readQueue() per loop
  TuningRegistry.slice();         // the rate-gated half; a SliceScheduler would call this for you
  m_tuner.periodic();
  CommandScheduler.getInstance().run();
}
```

**Cross-doc action (§19):** add a `tuning-standalone` row to `DESIGN.md` §11c's matrix and a fifth fixture to `IncrementalAdoptionTest`, so this shape is compiled in CI rather than only written down.

**If your team only owns two controllers,** say so out loud and take the friction:

```java
private final TuningWizard m_tuner =
    TuningWizard.using(m_operator).acknowledgeSharedController(
        "Only two controllers at this event; wizard is Test-mode only and the operator is briefed");
```

That string is logged verbatim into every tuning report and shown as a persistent warning alert for the rest of the session. Without it, `TuningWizard.using` sees port 1 in `ControlMap`, raises `Alerts.error("Tuning", ..., MatchImpact.PIT_ONLY)` naming the conflict, and refuses to leave `IDLE`.

**Two things about the wizard running from `robotPeriodic()`.** It runs unconditionally, and that is safe — but not for the reason the first draft of this design claimed. It is safe because `TuningSupervisor.arm()` throws unless `MatchContext.isDiagnostics()` (§7.3 precondition 7). During teleop the wizard's state machine still runs and still publishes narration, and every button on port 2 does exactly nothing to a motor. The FMS hard-return is a second, weaker belt: it stops the wizard from *displaying* during a match, but the property that makes a driver's trigger-pull harmless on a practice field is the mode check, not the FMS check. And the `IllegalStateException` that `arm()` throws when a precondition fails is caught inside `TuningWizard.periodic()` (§7.3, §8.3) — it never reaches `robotPeriodic()`, because a library that kills the robot code loop with its own throw has violated "degrade, never crash" no matter how good the message was.

### 14.3 What the team then does

```
$ ./gradlew simulateJava
  # Elastic opens. Pick "Elevator". Hold RT. Eleven steps, about eight minutes the first time.
  # Sim promotion recorded. Gains written to src/main/deploy/rootstock/gains.json.

$ git diff
  src/main/deploy/rootstock/gains.json | 14 +++++++-------

$ ./gradlew deploy
  # On the real robot: Test mode, pick "Elevator", hold RT.
  # Same eleven steps. Same plots. Real numbers this time.
  # Press A to save -> /home/lvuser/rootstock/gains.json
```

Total tuning-specific team code written: **two lines.** Total redeploys required to tune: **zero.** Total numbers retyped from a laptop: **zero.**

### 14.4 The same thing without Rootstock's mechanism layer

A team that just wants live sliders, or that has hand-rolled subsystems and does not want `PositionMechanism`, writes an adapter and gets tunability and the diagnostics for free. **This is [`DESIGN.md`](../DESIGN.md) §11b step 4's ~30-line adapter, and it is deliberately the *first* example in `docs/graduation.md`, not a footnote** — burying the escape hatch is exactly the mistake that cost a competing library its users.

```java
package frc.robot.subsystems;

import com.ctre.phoenix6.controls.NeutralOut;
import com.ctre.phoenix6.controls.VelocityVoltage;
import com.ctre.phoenix6.controls.VoltageOut;
import com.ctre.phoenix6.hardware.TalonFX;
import edu.wpi.first.math.system.plant.DCMotor;
import edu.wpi.first.wpilibj2.command.Subsystem;
import edu.wpi.first.wpilibj2.command.SubsystemBase;
import java.util.Optional;
import java.util.OptionalDouble;
import org.rootstock.control.*;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.SiDomain;

public class Shooter extends SubsystemBase implements TuningTarget {

  private static final double MOI_KG_M2 = 0.0021;

  private final TalonFX m_motor = new TalonFX(25);
  private Gains m_gains = Gains.pid(0.30, 0.0, 0.0).withKs(0.15).withKv(0.121).withKa(0.004);

  // ---- identity and shape ------------------------------------------------------------
  @Override public String tuningName()                { return "Shooter"; }
  @Override public MechanismArchetype archetype()     { return MechanismArchetype.FLYWHEEL; }
  @Override public SiDomain siDomain()                { return SiDomain.ROTATIONAL_RADIANS; }
  @Override public ControlLocation controlLocation()  { return ControlLocation.ON_MOTOR_DIRECT; }

  // ---- actuation: TuningSupervisor is the only legal caller ---------------------------
  @Override public void setVoltage(double v)          { m_motor.setControl(new VoltageOut(v)); }
  @Override public void stop()                        { m_motor.setControl(new NeutralOut()); }

  // ---- measurement, all in SI ---------------------------------------------------------
  @Override public double measuredSi()     { return m_motor.getPosition().getValueAsDouble()     * 2 * Math.PI; }
  @Override public double velocitySi()     { return m_motor.getVelocity().getValueAsDouble()     * 2 * Math.PI; }
  @Override public double accelerationSi() { return m_motor.getAcceleration().getValueAsDouble() * 2 * Math.PI; }
  @Override public OptionalDouble appliedVolts() {
    return OptionalDouble.of(m_motor.getMotorVoltage().getValueAsDouble());
  }
  @Override public OptionalDouble statorCurrentAmps() {
    return OptionalDouble.of(m_motor.getStatorCurrent().getValueAsDouble());
  }

  // Phoenix 6 reports the split directly, so the wizard can tell "raise kS" from "raise kP".
  // Only valid for voltage-output requests; setClosedLoopGoalSi below uses VelocityVoltage.
  @Override public Optional<Double> getFeedbackVolts() {
    return Optional.of(
        m_motor.getClosedLoopOutput().getValueAsDouble()
      - m_motor.getClosedLoopFeedForward().getValueAsDouble());
  }

  // A flywheel has no meaningful absolute position, so RotorOnly is correct and isHomed()'s
  // default (true for FLYWHEEL / DRIVE_VELOCITY) is already right. A POSITION archetype could not
  // get away with either of these lines -- see section 7.3.2.
  @Override public PositionReference positionReference() { return new PositionReference.RotorOnly(); }

  // ---- physical description ------------------------------------------------------------
  @Override public TravelLimits travelLimits() { return TravelLimits.unbounded(); }
  @Override public PlantPrior plantPrior() {
    return PlantPrior.flywheel(DCMotor.getKrakenX60Foc(2), Reduction.of(1.0), MOI_KG_M2);
  }

  // ---- gains ----------------------------------------------------------------------------
  @Override public Gains gains() { return m_gains; }
  @Override public GainSink gainSink() {
    // Two lines of conversion, in the ONE place the library allows it. siUnitsPerMechanismRotation
    // is 2*pi for a rotational domain, which is the only number a Phoenix sink needs (section 4.2).
    return Phoenix6GainSink.of(m_motor, GravityMode.NONE, /* siPerRotation */ 2 * Math.PI)
                           .andThen(g -> m_gains = g);
  }

  // ---- closed loop (optional; the identification steps do not need it) --------------------
  @Override public boolean supportsClosedLoop() { return true; }
  @Override public void setClosedLoopGoalSi(double radPerSec) {
    m_motor.setControl(new VelocityVoltage(radPerSec / (2 * Math.PI)));
  }

  @Override public Optional<Subsystem> requirement() { return Optional.of(this); }
}
```

About thirty-five lines, all of them things the subsystem already knew. Three carry obligations worth naming:

- **`positionReference()` is not defaulted.** A mechanism that cannot say how it knows where it is must be forced to think about that at compile time rather than discover it when an arm swings.
- **`getFeedbackVolts()` *is* defaulted to empty**, because guessing at that number is worse than not having it (§9.3.1).
- **`controlLocation()` is not defaulted**, because it decides the measurement-delay row (§9.2.1) and there is no safe default across `RIO_FULL` and the three on-motor variants. It is one line, and it is the line that makes the LQR result trustworthy.

Registration is `RootstockRegistry.addAll(m_shooter, ...)` — the same one call as §14.2. There is no separate `TuningRegistry.register` for a team to remember (D27).

---

## 15. What we deliberately do NOT do

Every line here names the existing tool that already does the job, because "we integrate best-in-class tools, we do not reimplement them" is the project's stance and this domain is the one most tempted to violate it.

| We do not build | Already done by | Our relationship to it |
|---|---|---|
| A PID controller | WPILib `PIDController`, `ProfiledPIDController`; Phoenix 6 `Slot0Configs`; REVLib `ClosedLoopConfig` | We compute the numbers that go in them. `Controllers.pid(...)` is a two-line factory, not a controller. |
| Feedforward maths | WPILib `SimpleMotorFeedforward`, `ElevatorFeedforward`, `ArmFeedforward` | We call `calculateWithVelocities(...)` and mutate gains via the existing `setKs/setKv/setKa/setKg` setters. |
| Motion profile generation | WPILib `TrapezoidProfile`, `ExponentialProfile` | We call `ExponentialProfile.Constraints.fromCharacteristics(maxInput, kV, kA)`. That one call is the whole "you never guess a max velocity again" feature. |
| An LQR solver | WPILib `LinearQuadraticRegulator` (+ `latencyCompensate`) | We construct it from `LinearSystemId.identifyPositionSystem/identifyVelocitySystem` and read `getK()`. Twelve lines total. |
| A least-squares decomposition | WPILib `Matrix.solveFullPivHouseholderQr` (EJML underneath) | We accumulate normal equations in `double[][]` and hand a 3x3 or 4x4 to WPILib to solve, through a one-method `LinearSolver` seam so `org.rootstock.pure` keeps its zero-WPILib-import rule. |
| The quasistatic/dynamic characterization motion | WPILib `SysIdRoutine` + `SysIdRoutineLog` | We generate the two callbacks and derive a safe config, then run WPILib's own commands. §6.1. |
| The SysId analysis GUI | WPILib SysId | Fully supported as an escape hatch. We write a clean single-routine WPILog so it works first try. We just do not *require* the laptop. |
| A plotting application | AdvantageScope | We publish `/RootstockTuner/plot/*` as plain NT doubles and get graphing, tuning mode, and log analysis for free. |
| A dashboard | Elastic | We ship a generated layout JSON and use only widgets Elastic already has. |
| An NT listener framework | WPILib `ntcore` | One `NetworkTableListenerPoller`, one `readQueue()` per loop, dispatch by topic name. Nothing custom on the wire. |
| A logging framework | **AdvantageKit — a REQUIRED dependency** (maintainer decision 3), not one of three options | We publish; we never own the logger. ~~Our types are Epilogue-friendly and our tunables can ride AdvantageKit's `LoggedNetworkNumber` when it is present.~~ **Both clauses withdrawn:** there is no Epilogue compatibility (a team on Epilogue or DogLog cannot adopt Rootstock without switching loggers) and the AdvantageKit dependency is unconditional. |
| A replay-safe dashboard-input wrapper | AdvantageKit `LoggedNetworkInput` / `LoggedNetworkNumber` | **Here we do own one thing, and the reason is stated in §5.6:** `LoggedNetworkNumber.periodic()` reads NetworkTables once **per instance**, which contradicts **D11a**'s one-JNI-call-per-loop requirement. We keep AdvantageKit's *mechanism* — one hand-written `LoggableInputs` through `Logger.processInputs` — and supply the single poller ourselves. That is one struct and one drain method, not a framework. |
| Deterministic log replay | AdvantageKit | We are replay-*safe* (§5.6) and we consume replayed data for offline refits. We do not implement replay. **Under decision 3 that safety is now a guarantee rather than a conditional property**, which is what makes `RootstockReplayVerify` (M20) meaningful. |
| Physics simulation models | WPILib `ElevatorSim`, `SingleJointedArmSim`, `FlywheelSim`, `DCMotorSim`, `BatterySim`; maple-sim for the field | The sim-first gate runs the recipe against whatever `RootstockSim` provides (D18). |
| A vendor configuration tool | CTRE Phoenix Tuner X, REV Hardware Client | We never touch firmware, device IDs, or CAN configuration. We write gain slots only, through `GainSink`, on the non-blocking `applyFast` path. |
| Swerve module bring-up (inverts, offsets, direction discovery) | `design/01`'s `HomingStrategy`/`describe()`/identity rules, `design/05`'s `DriveSelfCheck`, CTRE Tuner X Swerve Generator, YAGSL | We *check* that bring-up was done (§7.5, §8.10, §8.11) and refuse to tune a wrong-signed mechanism. We do not do the bring-up. |
| A declarative state machine for the wizard | WPILib 2027 Commands v3 ships one | Our wizard is a plain enum-driven loop with no `Command` inheritance, so it will port onto v3 without being a competing framework. |
| Relay (Astrom-Hagglund) autotune | Nobody in FRC ships it, and the community says hand tuning beats it | Deliberately rejected on safety and pedagogy grounds. §9.1. |
| Ziegler-Nichols tuning | Same | Same. It requires driving the loop to sustained oscillation, which is unacceptable on a geared arm. |
| A web server | WPILib `edu.wpi.first.net.WebServer` | The optional richer web UI is **in no milestone M1–M24 and is therefore outside v0.1** (see §18 question 5). If it is ever built it would be static files served by WPILib's server. |
| Interactive PID teaching simulators | WPILib's four browser tuning tutorials | We do not build a new PID explainer. We build the **bridge**: the same two stacked plots, on the student's actual robot, and a practice mode that ships WPILib's own reference plants (§13.11). |

---

## 16. Testing plan

The single most damaging failure mode for a library like this is a doc example that does not compile or a physics claim that is wrong — one wrong `sin`/`cos` destroyed a competing library's credibility in a single forum post. So the test suite is part of the deliverable, not an afterthought.

### 16.1 Pure-math tests (no HAL, run in CI, milliseconds)

| Test | Asserts |
|---|---|
| `FeedforwardRegressionTest` | Feed synthetic data generated from known kS/kV/kA/kG through `add(...)`; assert recovery to within 0.5%. One case per archetype. Includes a noise case (sigma = 0.05 V) asserting recovery within 3% and R2 > 0.95. **The ARM case injects a non-zero `horizontalReference` and asserts recovery is still within 0.5%** — the regression that would have caught §6.4's raw-position regressor. |
| `FeedforwardRegressionRankTest` | A pure-ramp dataset with no acceleration content throws `IdentificationException(FitFailure.RANK_DEFICIENT)` naming the `a` column. |
| `FeedforwardRegressionQualityTest` | The closed-form `SSE = yty - 2 b.Xty + b.XtX.b` identity matches a brute-force residual sum over stored samples, to 1e-9. |
| `PlantPriorDerivationTest` | All five derived priors of §6.5, against `DCMotor.getKrakenX60Foc(2)` and `LinearSystemId.createElevatorSystem`, to 1e-6 relative — **including the explicit assertion that `kGprior` does NOT depend on how the motor count is expressed**: `PlantPrior.elevator(getKrakenX60Foc(2), ...)` and a hand-built two-motor `DCMotor` must give the same `kGprior`, and a one-motor `DCMotor` on the same mechanism must give **twice** it. This is the test the factor-of-n bug would have failed. |
| `FeedbackDesignerTest` | For a known kV/kA, halving `maxAcceptableError` increases kP; doubling `maxControlEffort` increases kP; `latencyCompensate` with a positive delay reduces kP. Monotonicity, not exact values, because the exact Q/R construction is `[UNVERIFIED]` against SysId. Separately: the **continuous** `LqrDesign` solver satisfies §9.2.0's `kP == uMax/eMax` identity to 1e-9. |
| `StepResponseAnalyzerTest` | Synthetic second-order responses at zeta = 0.05 / 0.3 / 0.7 / 1.2 classify as `OSCILLATING` / `OVERSHOOT_RING` / `GOOD` / `SLUGGISH`. A divergent response classifies `UNSTABLE`. A response with a 5% offset classifies `STEADY_STATE_ERROR`. Log-decrement zeta recovery within 0.05 of the true value. |
| `StepResponseMetricsTest` | Rise time, overshoot, settle time and oscillation frequency computed on an analytic second-order step match closed-form values within 2%. Plus §10.2's worked triangular-profile derivation (0.34925 m at 1.6 / 6.0 → 0.341199 s) to 1e-6. |
| `GainsTest` | `Gains` has exactly seven components. `with(GainId, v)` round-trips through `get(GainId)` for **every one of the seven ids**, in both directions, and `with` is total (no id throws, no id substitutes). `Gains.UNTUNED.isUntuned()` is true and `Gains.pid(0,0,0).isUntuned()` is false. `Gains.zero()` **does not exist** — asserted by reflection, because its absence is the D2c contract. |
| `ControlConfigIntegralTest` | `ControlConfig.Builder.integral(kI != 0, iZone, iMaxVolts = 0)` produces a FATAL `ConfigError` naming `iMaxVolts`, and does **not** throw. A live `/Tuning/<M>/kI` edit that would leave `iMaxVolts == 0` is rejected with the same sentence published to `/RootstockTuner/result/warnings`. |
| `TravelLimitsTest` | `new TravelLimits(0, 1.397, 0.019).validate("Elevator")` returns one FATAL whose message contains the computed minimum `0.02794`; `softMargin = 0.60 * range` returns one FATAL naming the 40% cap; `TravelLimits.unbounded().validate(...)` returns `List.of()`. **Nothing in this class throws** — asserted by `assertDoesNotThrow` on every illegal combination. |
| `PredictStepTest` | The answer key is computed, never authored: for a plant with known kV/kA, gains giving `zeta < 0.7` must key option (1) "overshoot and bounce", `zeta > 1.2` must key option (2) "slow and short", and between them option (3) "clean". Boundary cases at exactly 0.7 and 1.2 are pinned. The §8.4 worked question (`kP 128.00, kA 0.060, kD 0, kV 5.000 → zeta 0.9021 → option 3`) is a fixture. Every shipped `Question` has exactly three options and a `whyWrong` entry for each incorrect index. |
| `LqrPanelMathTest` | `wn = sqrt(kP/kA)` and `zeta = (kD + kV)/(2*sqrt(kP*kA))` as rendered in the §9.2.2 panel match an analytic second-order plant with known `wn`/`zeta` to 1e-9, **and match the numbers printed in §9.2.2's worked block** (36.5148 rad/s, 5.8116 Hz, 2.2662). Monotonicity: `zeta` strictly decreases in kP, strictly increases in kD and in kV. |
| `LqrWarningTest` | Each of the five §9.2.2 warnings fires exactly on its stated condition, and the saturation warning is evaluated against the **refinement step magnitude**, not `maxAcceptableError`. A fixture asserts that on the §9.2.2 worked example the saturation, noisy-derivative and over-damped warnings fire and the aliasing and under-damped warnings do not. |
| `ExpressRecipeTest` | `TuningRecipe.express(archetype)` contains no `PredictStep` and no `StepResponseStep` in `REFINE` mode, and contains the **same** `PreflightStep` instance as `teaching(archetype)`. Express may drop teaching; it may never drop an interlock. |
| `GainConversionTest` | Canonical → Phoenix → canonical and canonical → REV → canonical round-trip for all seven gains within 1e-9, for both SI domains. Pins the §4.2 table **and its worked block**: `kP 128.000 × 0.2794 == 35.7632`, `kV 5.000 × 0.2794 == 1.3970`, `kS` unchanged, and the REV row divided by `Vnom` (and by 60 for kV/kA). |
| `GeometryDerivationTest` | `22 teeth × 0.25 in × 2 stages == 11.000 in == 0.279400 m` exactly, and the effective radius `U/(2π) == 0.0444679 m` to 1e-6. This is the test that would have caught `0.0879`. |
| `PlantPriorValidationTest` | A null motor and a zero `Reduction` each produce one FATAL `ConfigError` naming the field and the invert flag. Nothing throws. |
| `LessonsTest` | Every lesson non-empty, < 1800 chars, free of forbidden jargon tokens. |
| `LessonsNumericClaimTest` | Every numeric claim in §13.12's table matches the constant in the code that produces it. Owns the iteration count, the `1.6 / 2^ITERATIONS == 1/640` bracket identity, the 3%-of-travel guard, the 0.7 damping threshold, `sqrt(3) = 1.732`, `ln(9) * tau`, and the `(kS+kG) - abs(kS-kG) == 2*min(kS,kG)` breakaway identity. |
| `RefinementRuleTest` | Every `ResponseClass` maps to exactly one update rule; every rule respects its cap; six iterations from a deliberately bad starting kP converge or terminate without exceeding `8 * kP_lqr`. Both `getFeedbackVolts()` branches — present and empty — are covered, and the empty branch's message names **only** `ControlLocation.RIO_FULL`. |
| `TuningSupervisorCallerTest` | An ArchUnit rule: no class outside `org.rootstock.control.TuningSupervisor` calls `TuningTarget.setVoltage`. Release-blocking. |

### 16.2 Sim-integration tests (HAL, `SimHooks`-stepped, run in CI)

Run against WPILib's own plants so the answers are checkable:

| Test | Asserts |
|---|---|
| `FlywheelRecipeSimTest` | Full recipe against a `FlywheelSim` built with the WPILib tutorial's plant converges to kV within 15% of the documented 0.0075 and produces a `GOOD` final response. |
| `ElevatorRecipeSimTest` | Same, against `ElevatorSim`; kG within 5% of 2.28, kV within 15% of 3.07, kA within 30% of 0.41. (kA gets the loosest band; it is the hardest gain to measure and SysId's own docs say so.) **Plus a second fixture on this document's own 12:1 plant** asserting the full-travel verify of §8.7 step 11 sweeps `positionMin → positionMax → positionMin` **without an abort** — the regression for the soft-limit-vs-supervisor-band mismatch. |
| `ArmRecipeSimTest` | Same, against `SingleJointedArmSim`; kG within 5% of 1.75, kV within 15% of 1.95. Plus a deliberately-offset-zero case asserting the three-angle check reports a residual phase error within 2 degrees of the injected offset, **with a non-zero `horizontalReference` configured**, so the check measures a real mis-zero rather than the regressor's own omission. |
| `TurretRecipeSimTest` | kV within 15% of 0.15; final kP/kD within a factor of 2 of 0.3 / 0.05. |
| `SafetyAbortTest` | **Twelve tests, one per `AbortReason`.** Each injects the condition into a sim target and asserts the routine stops within 3 loops, the mechanism is neutral, and the published message names the cause. `SENSOR_INCONSISTENT` is injected by freezing the position signal while velocity keeps moving — the `optimizeBusUtilization` trap. **Two gravity cases:** a `LIMIT_APPROACH` on an arm must leave the arm *holding* at `kGbest`, not falling; and an `ENABLE_RELEASED` on a brake-mode arm at mid-travel must leave it within 2 degrees of where it was after one second. **And one ordering case:** with a coast borrow active, `abort()` must call `restoreNeutralMode()` before `stop()`. |
| `SafetyEnvelopeTest` | The strict-inequality sweep of §7.1, over four ranges **× five margin fractions {0.02, 0.05, 0.10, 0.20, 0.40}**, per position archetype. Revision 2's version used only `0.02 * range`, which is why the 5%-and-above degeneracy survived a full revision. Also asserts `positionMax > positionMin` and that an over-constrained short axis yields a FATAL `ConfigError` rather than an inverted band. |
| `RefinementBandTest` | For the same range × margin sweep, the refinement start `softMin + 0.35*range` and end `+ 0.25*range` both lie strictly inside `[positionMin, positionMax]`, or the recipe refuses with the "not enough safe travel" message. |
| `SimPromotionGateTest` | The wizard refuses to arm a real-flagged target with no promotion record; accepts after **all nine** perturbed runs contain the mechanism; refuses again after `configHash` changes. `runsCompleted` must equal 9 or the promotion is void. Asserts the `worstMarginToHardStopSi == worstMarginToLimitSi + guard` identity. **And the failing case, which is the point:** a plant with 6x kA inside a 20-degree safe band reaches a hard stop in at least one run, and the gate must **refuse** and publish `worstCaseDescription`. A gate with no failing test case is a gate nobody has checked. |
| `WizardTestModeTest` | With `MatchContext` simulated into teleop-enabled, `TuningSupervisor.arm()` throws and `commandVolts` is never reached, **while every wizard button is being held**. Repeated for autonomous and for disabled. Then in diagnostics-enabled, the same sequence arms and moves. **And the containment assertion:** no `IllegalStateException` escapes `wizard.periodic()` in any of those cases, and `/RootstockTuner/safety/message` carries the precondition text. This is the test that proves §7.4.1's and §7.3's claims rather than asserting them in prose. |
| `SharedControllerTest` | Constructing `TuningWizard.using(controller on a ControlMap-registered port)` without `acknowledgeSharedController` raises an `Alerts.error(..., MatchImpact.PIT_ONLY)` whose text names the port number, and the wizard never leaves `IDLE`. With the acknowledgment, it proceeds and the reason string appears verbatim in `report()`. |
| `CoastRiskTest` | Arming a `hasGravity()` target whose `neutralMode()` is `COAST`, or empty, throws from `arm()` with the §7.3.1 message; with `acknowledgeCoastRisk("...")` it proceeds and the reason appears verbatim in `report()` and on `/RootstockTuner/coastRiskAck`. |
| `HoldBisectionSafetyTest` | Against `SingleJointedArmSim` with a **deliberately 3x-wrong `PlantPrior` mass**: the arm never leaves a 3% band around its start position across all 10 iterations, no probe exceeds `PROBE_SECONDS`, an aborted probe still narrows the bracket (assert `hi - lo` strictly decreases every iteration regardless of outcome), and `recentre` returns the arm to within the settle band. **Four revision-4 cases:** (a) a **two-motor** `SingleJointedArmSim` asserting the `[0.2, 1.8] × kGprior` bracket **contains** the true kG — the factor-of-n regression; (b) an inverted-sign case (a wrist whose positive direction is downward) asserting `gSign == -1` and a negative kG within 5% of truth; (c) a **brake-mode** case where `overrideNeutralMode` returns false, asserting the step takes the `SIGN_BREAKAWAY` path, recovers the correct `gSign`, and **does not** set kG = 0; (d) an injected stiction band larger than `kP * toleranceSi`, asserting `recentre` terminates within `m_recentreBudget` and the step ends `RETRY_SUGGESTED` holding `kGbest`. |
| `PollerDrainTest` | With 12 mechanisms × 17 topics registered, one loop of `TuningRegistry.drainPoller()` performs exactly **one** `readQueue()` call (asserted with a counting `NetworkTableInstance` fake) regardless of tunable count, and exactly one `Logger.processInputs("Tuning", …)`. Pins D11a(b). |
| `TunableFlagTest` | `design/03` contract **C8**. `TuningRegistry.tunableFlag("Vision", "camera0Enabled", true)` publishes a **boolean** topic at `/Tuning/Vision/camera0Enabled`; the handle satisfies `BooleanSupplier` and is accepted by `VisionFilters.enabledWhen(...)` without a cast; a dashboard write is picked up by the **same** `drainPoller()` that carries doubles (the `readQueue()` count from `PollerDrainTest` is unchanged by adding flags); the value round-trips through `TuningInputs.flagKeys`/`flagValues` in replay; with tuning disabled, `get()` returns the compile-time default and the topic is never read; and `tunableFlag("Elevator", …)` against a registered mechanism name is refused with a FATAL `ConfigError` rather than publishing an eighteenth key under `/Tuning/Elevator/`. |
| `PersistencePrecedenceTest` | All four tiers, per-value merge, `configHash` mismatch rejection with `MatchImpact.BLOCKS_MATCH`, malformed JSON degrades to a `PIT_ONLY` warning rather than a crash, atomic write survives a simulated interrupt. |
| `PersistenceSchemaTest` | `gains.json`'s `gains` keys are exactly `GainId.values()` and its `control` keys are exactly the ten `ControlConfig` tunables of §5.5. A file carrying `profile` or `tolerance` inside `gains` is migrated with a named warning, not silently accepted. |
| `ReplaySafetyTest` | Scans `org.rootstock.tuning` and `org.rootstock.control` for `Timer.getFPGATimestamp`, `Timer.getTimestamp`, `Math.random`, `new Thread`, direct `DriverStation`/`Filesystem`/`Alert` references, and raw `HashMap` iteration in output paths. Fails the build on a hit. |
| `TuningAllocationTest` | 1000 loops with tuning disabled: **zero** allocations attributable to the tunable path. 1000 loops with tuning enabled and nothing changing: allocation is **bounded and constant** in tunable count (one `NetworkTableEvent[]` per loop), which is the honest claim §5.8 makes. |
| `NtSchemaTest` | Boots the registry with three mechanisms and asserts every topic named in §5.5 and §12.2 exists with the documented type. **Cross-document:** the `/Tuning/<Mechanism>/` topic list must be byte-identical to the one `design/01` §9.6 documents. This is what keeps the shipped Elastic layout from silently breaking and the two specifications from drifting again. |

### 16.3 Documentation tests

Every fenced Java block in this document that is presented as usable code is extracted at build time from a compiled test source file, not hand-written in prose. CI fails if a snippet does not compile against the pinned WPILib and vendor versions. **This is a docs-as-tests target built at M24 and it is a release blocker** — it does not exist yet, and this document does not claim in the present tense that it does.

Three constraints the extraction enforces, all of which this document has violated at least once:

- **Java 17, `--release 17`, no preview features.** `HoldBisectionStep.bisect` originally dispatched on `ProbeOutcome` with a pattern-matching `switch`, which is preview in 17 and would not have compiled. It now uses `instanceof` patterns (final since Java 16). Sealed interfaces and records are fine. This matters beyond style: §17 promises the 2027 port is an import rewrite, and a preview feature is the one thing that would make that false.
- **Every example must reference types that exist.** `[SUPERSEDED-NAME]` §14.1 was written against `LinearMechanism.builder(...).gearing(...).drumCircumference(...)`, which is in no document. The extraction compiles §14 against the real `PositionConfig`/`PositionMechanism`/`Reduction`/`LinearAxis`, so a class that exists only in prose fails the build.
- **Every example must produce zero FATAL `ConfigError`s.** Revision 3's rule was *"every example must construct legally"*, which policed the symptom of a design that threw from compact constructors. The rule is now the right one: `DocExampleValidationTest` builds every `PositionConfig` in this document, runs `RootstockRegistry.addAll`, and asserts the collected error list is empty. §14.1's old `softMargin(0.03)` on 1.60 m of travel would fail that assertion — and, critically, would fail it as a *test failure* rather than as a dead robot.

### 16.4 What CI cannot test

Stated honestly, because over-trusting green CI ships confident bugs: CAN latency, real motor saturation under a sagging battery, belt slip, a wire falling out, the actual units of Phoenix's `getClosedLoopOutput()` (§18 q12), and the feel of a tuned mechanism. The pre-flight health check (§7.5) and the pit-time `TuningHealth.check(...)` (§10.5) are the on-hardware counterparts, and the tuning report (§11.5c) is the human review artifact.

---

## 17. The 2027 port

The port is designed to be mechanical. Concretely:

| 2027 change | Impact on this domain | Mitigation already in the design |
|---|---|---|
| `edu.wpi.first.*` -> `org.wpilib.*` | Every import | Imports only; no re-exported WPILib types in our public API except `Command`, `Subsystem`, `DCMotor` and the unit `Measure` types at the boundary. |
| Java 17 -> Java 25 | None | We use records, sealed interfaces and `var` only — all Java 17 safe. No preview features anywhere. |
| Commands v2 -> v3 (coroutines) | `TuningWizard.command()`, `SysIdSweep`'s command factories | The wizard is a plain enum loop driven from `periodic()`. `command()` is a five-line adapter. Every recipe, step, analyzer and solver is command-framework-agnostic. |
| `MotorController.set()` -> `setThrottle()` | None | We only ever command volts, through `TuningTarget.setVoltage`. |
| NT3 removed | None | NT4 only, already. `NetworkTableListenerPoller` is NT4 machinery. |
| Shuffleboard / SmartDashboard removed | None | Never used. This is why we rejected YAMS's `/SmartDashboard/...` path. |
| `Alert` API "likely to change" | `Alerts` facade (design/06) | Already isolated behind a facade, and revision 4 removed the last direct `Alert` references from this document. |
| `DriverStation`, `Timer`, `Filesystem` move | `MatchContext`, `Clock`, `Platform` facades (design/06) | Same: revision 4 routed every remaining direct call through them (`DESIGN.md` §16 item 3). |
| WPILib Tunable API lands | `TunableTransport` gains a **second** implementation | §5.7. `TunableDouble`'s public surface does not change. *(Revision 3: "third" was counted against `Nt4TunableTransport`, which decision 3 deleted.)* `[SUPERSEDED-NAME]` Over a 3–4 year runway this is **likely rather than speculative** — risk R20, re-asked every kickoff. |
| `SysIdRoutine` may move/change | `SysIdSweep` | One class, ~200 lines, isolated. Worst case we own the sweep motion outright — the regression, LQR, diagnostics and wizard are unaffected. |
| Field origin / kinematics changes | None | Nothing in this domain touches field frames, poses, or kinematics. Deliberately. |
| Units: mutable `Measure` removed | None | We use immutable `Measure` at the boundary and plain doubles internally. |
| AdvantageKit must publish for 2027 | **Existential** | If AdvantageKit does not ship for WPILib 2027, **Rootstock does not ship** — R18, now High and ACCEPTED, with the three-tier contingency in [`ROADMAP.md`](../ROADMAP.md) §4.2. Nothing in this document can mitigate that; it is a dependency, not a design choice. |

**Two source-set halves, one jar.** The HAL-free half — `FeedforwardRegression`, `LqrDesign`, the `StepResponseAnalyzer` metric core, `Reduction` — lives in **`org.rootstock.pure`** inside the single `dev.rootstock:rootstock` jar (**D28**), with the zero-`edu.wpi.first`-import rule enforced by **bytecode scan at the package level**, not by a Maven coordinate. It ports with an import rewrite because it has no imports to rewrite. The year-specific half — `TuningRegistry`, the NT publisher, `TuningSupervisor`, the wizard loop, persistence — is `org.rootstock.tuning.*` and `org.rootstock.control`.

> **Revision 3 corrections, retained.** (1) These are **source sets inside one jar**, not published artifacts; revision 1 described `rootstock-tuning-core` and `rootstock-tuning-runtime` as separately publishable, which D28 deleted. (2) The port is **M12**, the only date-triggered milestone. It arms at the first *confirmed* WPILib 2027 alpha (~Oct 2027), must complete inside the beta window, and at solo pace **preempts M11**. Development stays on 2026.2.2 / Java 17 through M11 and does not chase alphas. **There is no long-lived 2027 branch:** the generated-source variant and the dual-compile CI exist only inside M12 and are **deleted at its end**, because there are no external users on the 2026 line to protect ([`ROADMAP.md`](../ROADMAP.md) §7.2).

---

## 18. Open Questions

> **Numbering is stable.** [`DESIGN.md`](../DESIGN.md) §5.6 item 6 and §16 both cite *"design/02 OQ#12"* by number, so questions answered by the binding decisions are marked **ANSWERED** in place — the same supersession discipline [`DECISIONS.md`](../DECISIONS.md) uses — rather than deleted and the rest renumbered.

1. ~~**Who owns `Gains`?**~~ **ANSWERED by D1 and D1a (2026-08-07).** `org.rootstock.control.Gains`, shipped in the `rootstock` jar so mechanisms depend on it without depending on the tuning package, and it is **exactly seven doubles** — `(kP, kI, kD, kS, kV, kA, kG)`, all volts-per-SI, named-field construction only. `design/01`'s `org.rootstock.config.Gains` is deleted; `Gains.realOrSim(real, sim)` survives as a static. Everything revision 3's record also carried — gravity mode, profile constraints, tolerance, `iZone`, `iMaxVolts` — lives on `ControlConfig`. *(The original text read: "I have specified `org.rootstock.control.Gains` here because the tuning domain is what produces gain values and needs the canonical unit contract. The mechanism domain is equally plausible as the owner. This must be reconciled with domain 03 before either of us writes a line; a duplicated or divergent gain type would be fatal to the whole 'one unit system' argument." It was right about the stakes: the two documents shipped incompatible `Gains` types for two revisions, and §5.5's NT schema, `TunedValueStore`'s file format and the Elastic layout were all built on the wrong one.)*

2. ~~**Does `TuningTarget` belong in the tuning domain or the mechanism domain?**~~ **ANSWERED by D8 (2026-08-07): neither — it moves to `org.rootstock.control` in core,** together with `MechanismArchetype`, `TravelLimits`, `PlantPrior`, `GainSink`, `Controllers`, `SafetyEnvelope` and `TuningSupervisor`. That gives the property the original question was reaching for (a team implements the seam in ~30 lines without adopting the mechanism layer *or* the wizard) without inverting the dependency in either direction. `DESIGN.md` §11b step 4 and `docs/graduation.md` lead with it.

3. **The `kD` conversion to REVLib is approximate.** Phoenix's derivative time base is per-rps; REV's is per-second. The row is marked **[UNVERIFIED]** in §4.2 and `describeConversion()` must say so. Someone with a NEO on a bench needs to measure whether the scalar conversion is close enough to be useful, or whether the REV sink should refuse to convert kD at all and require it to be tuned natively.

4. **Do the LQR-derived gains match SysId's Feedback Analysis?** The exact Q/R construction inside `sysid` could not be read from source. §9.2.0's closed form makes the *continuous* answer checkable — `kP = uMax/eMax` — but WPILib's implementation discretizes, and SysId's own weighting is still unread. If they diverge materially, students who cross-check against the official tool will lose trust. Someone should run both on the same kV/kA and publish the comparison before 0.1 ships.

5. **Is a served web UI worth building at all?** *(Revision 2 asked "worth it in v0.2?"; there is no v0.2 — it is in no milestone M1–M24 and is therefore outside v0.1, so this is a post-v0.1 question and the honest answer for now is "not scheduled.")* Elastic covers everything functionally, but WPILib's browser tutorials are genuinely excellent and reproducing their exact interactive layout on the robot would be a stronger teaching artifact than a dashboard tab. The cost is an NT4 WebSocket client in JavaScript and a second UI to maintain. Also: ports 5800-5810 are conventionally open for team use, but that is **[UNVERIFIED]** against the 2027 game manual and must be re-checked at kickoff.

6. **Refinement on on-motor loops.** When the closed loop runs at 1 kHz on the device (`ControlLocation.ON_MOTOR_PROFILED`, `ON_MOTOR_DIRECT`, `RIO_PROFILE_MOTOR_LOOP`), our 50 Hz `SampleBuffer` aliases the response. Rise times below ~60 ms will be measured badly. Options: raise the signal update frequency in sim and on hardware for the duration of a refinement step (CTRE explicitly recommends higher rates plus a `Notifier` for better simulated PID fidelity), or accept the aliasing and widen the `GOOD` thresholds for fast mechanisms. I lean toward the former but it needs measurement. **Note this document's own elevator is squarely in the danger zone:** §9.2.2's panel reports `fn = 5.81 Hz` and a 0.31 s rise, which is fine, but a stiffer mechanism at the same 4 V / 1 cm preference would not be.

7. **How should `DRIVE_VELOCITY` handle a robot that genuinely cannot get 3 m?** Some teams tune in a hallway. The on-blocks detector will correctly refuse, but there is no graceful degraded path today. A "short-run mode" that fits kS/kV from a series of short pulses is possible but the kA estimate would be poor.

8. **Backlash compensation.** We *detect* backlash and tell the student to fix it mechanically, which is the right primary answer. But some mechanisms ship with irreducible slop. Do we eventually offer a directional-offset compensation term, or does that cross the line into hiding a mechanical problem in software? My instinct is that we do not, and we say why.

9. **Multi-motor mechanisms with a disagreeing follower.** The regression fits one applied-voltage signal. If a follower is fighting the leader (wrong `Follower.OPPOSED`), the fit is quietly wrong and the health check's current test may not catch it. A per-motor current comparison during the sweep would catch it; it needs `TuningTarget` to expose per-motor currents, which complicates the SPI. `design/01`'s follower-disagreement threshold (2% of total travel, enabled-only) catches the *position* case but not the *current* case.

10. ~~**Session length versus student attention.**~~ **ANSWERED — see §8.12.** The elevator recipe is eleven steps and about eight minutes with reading, times four mechanisms, on one shared robot, repeated after every mechanical change. That is a queue, not a lesson. We ship `TuningRecipe.express()` (identification only, one narration screen, ~90 s) alongside the full teaching recipe; `TEACHING` is the non-overridable default in simulation, hardware asks once and then remembers the choice per mechanism. Express drops teaching and never drops an interlock, and `ExpressRecipeTest` pins that. The remaining sub-question, which is genuinely open: **does a student who only ever runs express on hardware still learn?** The `Predictions: n/m` line is our instrument for finding out, and we should look at real reports from 8793 and 9143 after one offseason before deciding whether express should be gated behind a completed teaching run.

11. ~~**Does `MechanicalHealthCheck` belong here or in the health domain?**~~ **ANSWERED by D23 (2026-08-07): it stays in tuning,** because it commands raw voltage and must go through `TuningSupervisor` — and `design/06` owns no voltage-commanding code by design. The passive half, `TuningHealth` ("is this mechanism still tuned?"), *is* registered as a `HealthSource` and *is* consumed by `design/06`. The original concern — that §7.6 promoted `MechanicalHealthCheck`'s `BLOCK` verdict to one of the two headline safety properties of this domain, which raises the stakes on getting the ownership right — is addressed by the split rather than by a move: the voltage-commanding check lives where the supervisor is, and the passive check lives where the monitors are.

12. **Does Phoenix 6's `getClosedLoopOutput()` read back in volts for a voltage-output request?** The 26.1 javadoc says only *"Closed loop total output"* and states no units (verified 2026-08-07). Our whole `residualVolts` diagnosis — and therefore every "raise kS, not kI" message the library exists to deliver — assumes it does for `PositionVoltage`/`VelocityVoltage`/`MotionMagicVoltage`. Someone needs a Kraken on a bench, a known kS, and ten minutes: command a voltage-output closed loop, hold it at steady state, and check that `getClosedLoopOutput() - getClosedLoopFeedForward()` is a plausible number of volts rather than a duty cycle. If it is a duty cycle, the adapter multiplies by the supply voltage and this document gets one more conversion row. If it is something else, the Phoenix path falls back to the REVLib-style Java reconstruction (§9.3.1) and we say so in `describeConversion()`. *(Cited by `DESIGN.md` §5.6 item 6 and §16 as a verification task, with this ten-minute procedure.)*

13. ~~**`LoopLocation` versus `ControlLocation`.**~~ `[SUPERSEDED-NAME]` **ANSWERED by D5 (2026-08-07): `ControlLocation` is canonical and `LoopLocation` is deleted.** Four values carry strictly more information than two, `RIO_PROFILE_MOTOR_LOOP` genuinely differs from both of the old ones, and it is **defaulted from the leader's `MotorSpec`** so a rookie never answers an expert question on line five of their first config. Tuning asks it exactly one question, `runsOnMotor()` (§9.2.1). *(§9.3.1 of revision 3 recorded a contrary decision — "`LoopLocation` is the tuning-domain SPI type and `ControlLocation` is the builder-facing type, and domain 03 owns a one-line total mapping between them" — and even taught the student both names inside one error message. `DESIGN.md` §5's preamble states that the integration decisions are binding and that no domain may re-litigate one; that note is deleted and this question is closed.)*

14. **Should `TuningRegistry`'s slice register a `RootstockTracer` budget of its own?** `design/06` gives every domain a per-slice loop-time budget. §5.8 states this domain's numbers, but the *enforcement* currently lives in `LoopTimingTest` on the CI container rather than in a runtime budget that fires an alert at an event. Cheap to add; needs a number that is not guessed.

---

## 19. What this sweep could NOT close

Revision 4 applied every binding decision to this document. Five things it found are **not** this document's to fix, and are recorded here so `DESIGN.md` §16 item 5 can carry them rather than losing them.

| # | Owner | Change required |
|---|---|---|
| 1 | [`DESIGN.md`](../DESIGN.md) §16 item 4 | The sweep record claims the four decisions were applied "to all ten documents" and its own document list is `design/01, 03, 04, 05, 06` — `design/02` is omitted, and it showed. Item 4 must admit the omission and record that revision 4 of this document closed it. |
| 2 | [`DESIGN.md`](../DESIGN.md) §11b step 4 | The ~30-line adapter needs three edits to compile against §3.1: `FeedbackSpec feedbackSpec()` → **`PositionReference positionReference()`** (design/01's `FeedbackSpec` answers a different question and lives in a package `control` cannot import); add **`ControlLocation controlLocation()`**; and `PlantPrior.flywheel(Reduction.of(1.0), MOI)` → **`PlantPrior.flywheel(DCMotor motor, Reduction reduction, double moiKgM2)`** — every prior in §6.5 is read off the motor curve, so the motor is required. |
| 3 | [`DESIGN.md`](../DESIGN.md) §11c | Add a **`tuning-standalone`** row to the adoption matrix (`rootstock`; `TuningRegistry.drainPoller()` + `TuningRegistry.slice()` + `wizard.periodic()` by hand; no `RootstockLifecycle`) and a fifth fixture to `IncrementalAdoptionTest`, so §14.2's standalone shape is compiled in CI. |
| 4 | [`design/01`](01-core-mechanisms.md) | (a) `SiDomain`'s constants are `LINEAR` / `ANGULAR`; **D3** names them **`LINEAR_METERS` / `ROTATIONAL_RADIANS`**, and `DESIGN.md` §11b uses the latter. (b) `Reduction` is declared in `org.rootstock.units`; **D7** and `DESIGN.md` line 792 put it in **`org.rootstock.pure.units`**. (c) `ControlLocation` is declared in `org.rootstock.hardware`; `DESIGN.md` §7's package tree puts it in **`org.rootstock.control`**, and it needs a **`runsOnMotor()`** accessor returning true for `ON_MOTOR_PROFILED`, `ON_MOTOR_DIRECT` and `RIO_PROFILE_MOTOR_LOOP`. (d) `ConfigError` and `Severity` must move to **`org.rootstock.pure`** so `org.rootstock.control`'s value types can return them without inverting the layering; `org.rootstock.config` keeps using them unchanged, and `ConfigError` needs a `callerFrame()` static. (e) `NeutralMode` must be reachable from `org.rootstock.control` — hoist it out of `MotorIO` to **`org.rootstock.control.NeutralMode {BRAKE, COAST}`**, imported by `MotorIO` and `ControlConfig`. (f) `LinearAxis.sprocket` should compute travel as **`teeth × pitch × stages` (0.279400 m)**, the exact chain advance, not `2π·r_pitch·stages` (0.280293 m, 0.32% high); `DescribeSnapshotTest`'s expected block regenerates. (g) `ControlConfig` needs the two integral topics (`iZone`, `iMaxVolts`) in §9.6's `/Tuning` list, and `PositionConfig.Builder` needs an optional **`tuningMargin(Measure<?>)`** plus the derived default `max(0.02 * range, 2 * tolerance)`. (h) The Phoenix backend passes **`MotionMagicJerk = constraints().jerk()` unconverted** while cruise and acceleration on the adjacent lines are converted — add `MechanismUnits.toOutputRps3(double)`, use it at both sites, and add a jerk row to `UnitsContractTest`. |
| 5 | [`DESIGN.md`](../DESIGN.md) §14 row 26 | The row still sells the sim-first promotion gate as the primary safety property of this domain. §7.6 demoted it two revisions ago: the two properties that carry the safety case are `MechanicalHealthCheck`'s `BLOCK` verdict and `arm()`'s position-reference preconditions, with the sim gate third and bounded ("it proves the envelope holds under plant error"). |

Two further items are **verification tasks**, not corrections, and both are already tracked: the Phoenix `getClosedLoopOutput()` units question (§18 q12, ten-minute bench procedure written) and the REVLib kD time-base question (§18 q3).

---

## Appendix A — File and class inventory

```
org.rootstock.control                      THE canonical control vocabulary (D8), in the rootstock jar
    Gains                       record: SEVEN doubles, volts-per-SI, named-field construction only (D1a)
                                + Gains.UNTUNED (D2c); Gains.zero() does NOT exist
    GainId                      enum: seven ids, one per Gains component; generates the NT schema
    GravityMode                 enum: NONE | CONSTANT | COSINE (D2a); GravityType does NOT exist
    ControlLocation             enum: ON_MOTOR_PROFILED | ON_MOTOR_DIRECT | RIO_PROFILE_MOTOR_LOOP
                                | RIO_FULL, plus runsOnMotor() (D5); LoopLocation does NOT exist  [SUPERSEDED-NAME]
    ControlLocationSource       enum: EXPLICIT | DEFAULTED
    NeutralMode                 enum: BRAKE | COAST  (hoisted here -- see section 19 item 4e)
    GainSink                    interface: canonical -> vendor conversion and apply
    Controllers                 factory: Feedforward / PIDController / profiles from Gains
    TuningTarget                interface: THE mechanism SPI
    MechanismArchetype          enum
    PositionReference           sealed: RotorOnly | HomedAgainstSwitch | Absolute | FusedAbsolute
                                | AssumeAtBoot  (NOT config.FeedbackSpec -- section 3.1)
    TravelLimits                record + validate(owner) -> List<ConfigError>; NEVER throws
    PlantPrior                  record (DCMotor + Reduction + mass/MOI/radius) + five derived priors
                                + validate(owner); NEVER throws
    SafetyEnvelope              record + derive(target) + validate(owner, limits)
    TuningSupervisor            the ONLY raw-voltage choke point; arm() / commandVolts() / check() / abort()
    AbortReason                 enum: exactly TWELVE values (section 7.2)

org.rootstock.pure.solvers                 HAL-free; zero edu.wpi.first imports, bytecode-scanned
    FeedforwardRegression       streaming OLS, O(1) memory
    FitFailure                  enum: INSUFFICIENT_DATA | RANK_DEFICIENT
    IdentificationException
    LqrDesign                   the continuous LQR solve behind FeedbackDesigner
    StepResponseAnalyzer core   the metric computations of section 10.2
    LinearSolver                one-method seam; wpimath supplies the implementation

org.rootstock.pure
    ConfigError, Severity       collected-not-thrown validation values (see section 19 item 4d)
    units/Reduction             positive-only, self-describing gearbox (D7)

org.rootstock.tuning
    TuningRegistry              tunables, targets, tuning mode; ONE NetworkTableListenerPoller;
                                drainPoller() (LifecycleHook 30) + slice() (SliceScheduler)
    TunableDouble               NT-backed double, cached field, no NT on the hot path
    TunableBoolean              implements BooleanSupplier; from TuningRegistry.tunableFlag(...)
                                (design/03 contract C8 -- the per-camera pit kill switch)
    TunableGains                the SEVEN-gain set for one mechanism, write-through, 10 Hz applyFast
    TunableTransport            interface + AdvantageKitTunableTransport (the only implementation);
                                WpilibTunableTransport reserved for the 2027 API (section 5.7)
    TuningInputs                ONE hand-written LoggableInputs for the whole /Tuning table (D24)
    FeedbackDesigner            LQR from kV/kA, over LqrDesign; Preferences + Suggestion
    MechanicalHealthCheck       idle mode / backlash / friction / slip / direction pre-flight (D23)
    HealthReport                record
    SimPromotion                record

org.rootstock.tuning.sysid
    SysIdSweep                  generated SysIdRoutine + envelope-derived config
    FeedforwardFit              record
    SampleBuffer                fixed-capacity ring buffer
    CentralDifferenceAccel      velocity -> acceleration filter for targets with no accel signal

org.rootstock.tuning.wizard
    TuningWizard                the state machine; the SOLE caller of TuningSupervisor.arm()
    WizardState                 enum
    WizardInputs                interface (gamepad-free path)
    TuningRecipe                ordered steps + envelope + preflight; Mode{TEACHING,EXPRESS}
    TuningStep                  interface (begin / periodic / isComplete / finish)
    StepContext                 interface
    StepResult                  record (incl. Optional<Boolean> predictionCorrect)
    Recipes                     the six built-in recipes, each in both modes
    steps/PredictStep           formative assessment; no motion; computed answer key
    steps/PreflightStep
    steps/BreakawayRampStep     kS, and the gravity-sign breakaway fallback
    steps/HoldBisectionStep     kG: explicit sub-state machine; sign probe (coast borrow OR breakaway),
                                physics bracket, in-window guard, time-budgeted recentre
    steps/SysIdSweepStep        kV, kA
    steps/LqrSuggestStep        kP, kD (+ the wn/zeta interpretation panel)
    steps/StepResponseStep      verify + refine; the only raiser of AbortReason.UNSTABLE_RESPONSE
    Lessons                     the teaching text
    Coach                       verdict -> plain language; branches on prediction right/wrong

org.rootstock.tuning.diagnostics
    StepResponseAnalyzer        typed wrapper over the pure metric core
    ResponseClass               enum
    ResponseVerdict             record
    TuningHealth                pit-time "still tuned?" check; registered as a HealthSource (D23)

org.rootstock.tuning.persist
    TunedValueStore             4-tier precedence, per-value merge, atomic write, setpoints by RobotId
    ValueExporter               Java block, JSON baseline, markdown report
    QualityRecord               record

org.rootstock.tuning.ui
    TunerPublisher              every NT topic in section 12.2
    ElasticLayoutGenerator      writes elastic-tuning-layout.json from the registry
```

**Deleted, and deliberately listed so a reader of an older revision knows they are gone:**
`[SUPERSEDED-NAME]` `GainStore` (→ `TunedValueStore`), `GainsExporter` (→ `ValueExporter`), `MechanismUnits`-the-enum (→ `org.rootstock.units.SiDomain`, D3), `LoopLocation` (→ `ControlLocation.runsOnMotor()`, D5), `Gains.GravityType` (→ `GravityMode`, D2a), `Gains.ProfileConstraints` (→ `MotionConstraints` on `ControlConfig`, D2), `Gains.zero()` (→ `Gains.UNTUNED`, D2c), `Gains.withIntegral/withGravity/withProfile/withTolerance` (→ `ControlConfig`, D1a/D1b), `org.rootstock.tuning.FeedbackSpec` (→ `org.rootstock.control.PositionReference`), and `Nt4TunableTransport` (deleted by maintainer decision 3 — there is no classpath without AdvantageKit).

## Appendix B — Verified API reference

Every WPILib signature this design depends on, confirmed against the WPILib 2026 Javadoc on 2026-08-06 unless a later date is given:

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

// DCMotor -- verified 2026-08-08 against the allwpilib source, not just the javadoc.
// https://github.com/wpilibsuite/allwpilib  wpimath/.../math/system/plant/DCMotor.java
DCMotor(double nominalVoltageVolts, double stallTorqueNewtonMeters, double stallCurrentAmps,
        double freeCurrentAmps, double freeSpeedRadPerSec, int numMotors)
//   this.stallTorqueNewtonMeters = stallTorqueNewtonMeters * numMotors;
//   this.stallCurrentAmps        = stallCurrentAmps * numMotors;
//   this.freeCurrentAmps         = freeCurrentAmps * numMotors;
//   this.rOhms                   = nominalVoltageVolts / this.stallCurrentAmps;      // scales 1/n
//   this.KvRadPerSecPerVolt      = freeSpeedRadPerSec / (nominalVoltageVolts - rOhms * this.freeCurrentAmps);
//   this.KtNMPerAmp              = this.stallTorqueNewtonMeters / this.stallCurrentAmps;  // UNCHANGED by n
static DCMotor DCMotor.getKrakenX60Foc(int numMotors)   // new DCMotor(12, 9.37, 483, 2, rpm(5800), n)
// CONSEQUENCE, and the reason section 6.5's kGprior changed:
//   KtNMPerAmp / rOhms == stallTorqueNewtonMeters_1 * numMotors / nominalVoltageVolts
//   -- it ALREADY carries the motor count. Dividing by motorCount again makes kGprior n times low.

// Linear algebra
static <R,C> Matrix<R,C> MatBuilder.fill(Nat<R> rows, Nat<C> cols, double... data)     // row-major
static Vector<N3> VecBuilder.fill(double, double, double)                              // and N1..N10
<R2,C2> Matrix<C,C2> Matrix.solveFullPivHouseholderQr(Matrix<R2,C2> other)
double Matrix.get(int row, int col)
// NOTE: Matrix has NO plusEqu(). Accumulate in double[][].

// Profiles and controllers
static ExponentialProfile.Constraints ExponentialProfile.Constraints.fromCharacteristics(double maxInput, double kV, double kA)
static ExponentialProfile.Constraints ExponentialProfile.Constraints.fromStateSpace(double maxInput, double A, double B)
PIDController(double kp, double ki, double kd, double period)
void PIDController.setTolerance(double positionTolerance)
void PIDController.setIZone(double iZone)
void PIDController.enableContinuousInput(double minimumInput, double maximumInput)
TrapezoidProfile.State TrapezoidProfile.calculate(double t, TrapezoidProfile.State current, TrapezoidProfile.State goal)

// SysId
SysIdRoutine(SysIdRoutine.Config config, SysIdRoutine.Mechanism mechanism)
SysIdRoutine.Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout, Consumer<SysIdRoutineLog.State> recordState)
SysIdRoutine.Config(Velocity<VoltageUnit> rampRate, Voltage stepVoltage, Time timeout)
SysIdRoutine.Config()                                    // 1 V/s, 7 V, 10 s
SysIdRoutine.Mechanism(Consumer<Voltage> drive, Consumer<SysIdRoutineLog> log, Subsystem subsystem, String name)
Command SysIdRoutine.quasistatic(SysIdRoutine.Direction direction)
Command SysIdRoutine.dynamic(SysIdRoutine.Direction direction)

// NetworkTables -- verified 2026-08-08. This is the D11a(b) single-poller path.
NetworkTableListenerPoller(NetworkTableInstance inst)
int  NetworkTableListenerPoller.addListener(String[] prefixes, EnumSet<NetworkTableEvent.Kind> eventKinds)
int  NetworkTableListenerPoller.addListener(Topic topic, EnumSet<NetworkTableEvent.Kind> eventKinds)
int  NetworkTableListenerPoller.addListener(Subscriber subscriber, EnumSet<NetworkTableEvent.Kind> eventKinds)
NetworkTableEvent[] NetworkTableListenerPoller.readQueue()
void NetworkTableListenerPoller.close()
// NetworkTableEvent.Kind constants include kImmediate, kPublish, kUnpublish, kProperties, kTopic,
// kValueRemote, kValueLocal, kValueAll ("Topic value updated (network or local)"), kLogMessage.
// NetworkTableEvent public fields: int listener, ConnectionInfo connInfo, TopicInfo topicInfo,
//   ValueEventData valueData, LogMessage logMessage, TimeSyncEventData timeSyncData
// ValueEventData public fields: int topic (handle), int subentry, NetworkTableValue value
//   plus Topic getTopic().  Dispatch key: event.valueData.getTopic().getName()

// Web
static void WebServer.start(int port, String path)
static void WebServer.stop(int port)
```

**AdvantageKit 26.x signatures verified 2026-08-08** (https://github.com/Mechanical-Advantage/AdvantageKit):

```java
// akit/src/main/java/org/littletonrobotics/junction/networktables/LoggedNetworkNumber.java
LoggedNetworkNumber(String key)
LoggedNetworkNumber(String key, double defaultValue)
double LoggedNetworkNumber.get()          // "Returns the current value"
void   LoggedNetworkNumber.set(double)    // "Publishes a new value. Note that the value will not be
                                          //  returned by get() until the next cycle"
void   LoggedNetworkNumber.setDefault(double)
void   LoggedNetworkNumber.periodic()     // "Updates internal value from NetworkTables (unless in
                                          //  replay mode) and processes inputs"  <-- ONE NT read
                                          //  PER INSTANCE. This is why section 5.6 uses one poller
                                          //  plus one LoggableInputs instead (D11a(b)).
double LoggedNetworkNumber.getAsDouble()  // implements DoubleSupplier

// Used by TuningInputs (section 5.6):
interface org.littletonrobotics.junction.inputs.LoggableInputs { void toLog(LogTable); void fromLog(LogTable); }
static void org.littletonrobotics.junction.Logger.processInputs(String key, LoggableInputs inputs)
```

**Phoenix 6 signatures verified 2026-08-07** ([CoreTalonFX](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/hardware/core/CoreTalonFX.html)):

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

**REVLib 2026 facts used above, verified via [`DESIGN.md`](../DESIGN.md) §5.6's own verification pass:**

```java
// Exists -- the arbitrary-feedforward overload the REV GainSink needs:
SparkClosedLoopController.setSetpoint(double setpoint, ControlType, ClosedLoopSlot, double arbFeedforward, ArbFFUnits)
// Fault queries: hasActiveFault(), hasStickyFault(), hasActiveWarning(), hasStickyWarning(),
//                getFaults()/getWarnings() returning Faults/Warnings.
// setReference is DEPRECATED, not removed.
// REVLib has kCos and NO arm-position-offset analog to Phoenix's GravityArmPositionOffset --
//   hence the Tier-1 Validation rule in section 4.2 requirement 2.
```

**Unverified items, restated in one place so a reviewer can find them:**

1. The exact Q/R construction inside SysId's Feedback Analysis (§9.2) — inferred from WPILib prose plus the public `LinearQuadraticRegulator` API, not read from `sysid` source.
2. §9.2.0's `kP = uMax / eMax` identity holds for the **continuous** ARE with Bryson weights; WPILib's `LinearQuadraticRegulator` **discretizes** at `dtSeconds`, so the returned value departs from it as the desired bandwidth approaches Nyquist. Tests assert monotonicity against the WPILib path and the identity only against the pure continuous solver.
3. Our `voltageFitR2` is not comparable to SysId's simulated-velocity or acceleration r-squared (§6.3); the thresholds in §6.4 are Rootstock's own.
4. The REVLib `kD` scalar conversion (§4.2) — the derivative time base differs between vendors. Open question 3.
5. Whether GradleRIO's deploy task deletes files under `/home/lvuser/deploy` that are absent from the source tree (§11.2). Our design does not depend on the answer.
6. Ports 5800-5810 being open for team use on the field (§12.1) — must be verified against the 2027 game manual at kickoff.
7. The final class and method names of WPILib's 2027 Tunable API (§5.7) — PR #7773 is open, not merged.
8. The **units** of Phoenix 6's `getClosedLoopOutput()` (§9.3.1). The method signatures are verified; the javadoc states no units and "volts for voltage-output requests" is our inference. Open question 12. The adapter returns `Optional.empty()` rather than guessing for every other request type.
9. The 0.32% disagreement between this document's chain-advance sprocket geometry (`teeth × pitch × stages` = 0.279400 m) and `design/01` §4.3's pitch-circle geometry (`2π·r_pitch × stages` = 0.280293 m). Not a physics uncertainty — the chain advance is exact — but a cross-document one until §19 item 4f lands.








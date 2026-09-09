# Rootstock Design 01 — Core: Hardware Abstraction, Mechanisms, Config, Superstructure

**Domain:** CORE
**Status:** Design complete (revision 4 — revision 3 plus the 2026-08-07 independent expert review), ready to implement
**Target:** WPILib 2026 (`edu.wpi.first.*`, Java 17) as the *build* line; WPILib 2027 (`org.wpilib.*`, Java 25, SystemCore) as the *shipping* line. See §10.
**Verified against:** Phoenix 6 26.1/26.2, REVLib 2026.0.x, WPILib 2026.2.2, PathPlannerLib 2026.1.2.
Every vendor API name in this document was either read out of the vendor javadoc during design or is marked **[UNVERIFIED]** inline.

> **Revision 4 correction notice, stated before anything else in this document.** The 2026-08-07
> review found that revision 3 asserted this same "read out of the vendor javadoc" promise while
> shipping a flagship control seam built on `MotionMagicVoltage.withVelocity(...)`, **which does not
> exist**. The promise was not kept, and the mechanism that was supposed to keep it — a
> compile-against-vendor test — did not exist either, because no code exists. Revision 4 rewrites
> the seam against re-verified javadoc (§3.5.6), adds the missing test to §11, and restates every
> "extracted from a compiled test" sentence in the **future** tense it actually deserves (§9).
> Do not read the header promise above as a claim that this has been machine-checked. It has not.
> It is checked by hand, at each revision, until M24 builds the CI job that checks it.

**Revision 2 changelog** (what an adversarial API-truth / feasibility / pedagogy review changed):

| # | Was | Now | §|
|---|---|---|---|
| 1 | `m_feedforward.calculate(pos, vel)` on a raw WPILib feedforward | `Controllers.Feedforward` (design/05 §control) over the verified `calculateWithVelocities` overloads | §6.2, §3.8 |
| 2 | `RIO_FULL` ran in user units with volts-per-SI gains (57.3× / 39.37× error) | `RIO_FULL` runs **entirely in SI**; the one place SI conversion happens | §4.4, §6.2 |
| 3 | `GravityArmPositionOffset = +horizontalReference` | **negated**, plus a Tier-1 ±0.25 rot range guard | §3.5 |
| 4 | Expo requests with unset `MotionMagicExpo_kV/_kA` (CTRE defaults 0.12 / 0.1) | derived from measured `kV`/`kA`, Tier-2 alert when unmeasured | §3.5 |
| 5 | `updateInputs` read signals `configureSignals` never subscribed | `SignalSet` — subscribe and read are one declaration; unsubscribed ⇒ `NaN`, never a frozen 0 | §3.5 |
| 6 | `.andThen(trigger::getAsBoolean)` (compiles as `Runnable`, no-op) | `.andThen(Commands.waitUntil(...))` | §9.3 |
| 7 | Flagship elevator: 45:1 gave 0.62 m/s free speed but the config asked for 1.6 m/s | 12:1, all four derived numbers recomputed and CI-asserted against `describe()` | §4.4, §5.4, §5.6, §9.1 |
| 8 | `applyVerified` (blocking, ×5) called from `periodic()` at 10 Hz | `applyFast` (0 s timeout) for gains; `applyVerified` for construction / disabled / self-test only | §3.9 |
| 9 | Setpoint sent once, forever; a device reset silently dropped the mechanism | `hasResetOccurred()` re-arm + 10 Hz setpoint heartbeat + `deviceResetCount` | §3.5 |
| 10 | `.foc(true)` swapped the whole request set and reinterpreted gains as amps | `.foc()` sets only `withEnableFOC`; `outputMode(TORQUE_CURRENT)` is a separate, `ConfigError`-guarded field that is **outside v0.1** (see revision-3 note at open question 13) | §3.5 |
| 11 | `MotorInputs implements LoggableInputs` inside `rootstock-mechanism` (ArchUnit violation) | `implements RootstockInputs`; AdvantageKit adapter owns `LoggableInputs` — **SUPERSEDED by revision 3 row 21: it is back to `LoggableInputs`, and rule 1 changed instead** | §1.1, §3.4 |
| 12 | Tier-1 validation threw from a record constructor → `ExceptionInInitializerError`, dead robot | errors are **collected**, not thrown; `RootstockRegistry` prints them all and enters **SAFE_MODE** | §5.6 |
| 13 | `AxisGoal.named("L4")` typos failed at button-press time, mid-match | names validated at construction with a "did you mean"; runtime path cannot fail silently; typed `Setpoint` handles are the documented default | §8.9 |
| 14 | Router tested the straight line between two configurations | Router tests the **axis-aligned bounding box** (the true reachable set of two unsynchronized profiles) + `synchronizedAxes` | §8.4 |
| 15 | No transition cost model, no reachability analysis, no honesty about the ceiling | `SuperstructureReport` + `characterizeTransitions()` + an explicit "we are less capable than 254's A*" statement | §8.8, §13 |
| 16 | "Every numeric field auto-registered by field name" (reflection) | explicit allowlist, no reflection; geometry/CAN IDs/sim params deliberately NOT tunable | §1.2 |
| 17 | Six string concatenations per mechanism per loop in `periodic()` | log keys precomputed in the constructor | §6.1, §6.2 |
| 18 | `throw new IllegalStateException("...This is a Rootstock bug.")` from `periodic()` | degrade, name itself, latch a no-op flag | §3.5 |
| 19 | `ControlLocation` was a required builder call (an expert question at line 5) | defaulted from the leader's `MotorSpec`, printed with provenance in `describe()` | §5.3 |
| 20 | `setPositionGoal(pos, arbFf)` — no velocity, so field-locked turret was unimplementable | `setPositionGoal(pos, rps, arbFf[, constraintOverride])` | §3.3, §6.4 |

**Revision 3 changelog** (maintainer decision 3, 2026-08: **AdvantageKit is a required dependency, not one backend among four**). This is a *reversal* of revision 2's backend-neutral logging seam. Everything else in revision 2 — the hardware seam, units, config, mechanisms, homing, superstructure — is unchanged and correct.

| # | Was (revision 2) | Now (revision 3) | § |
|---|---|---|---|
| 21 | `MotorInputs implements RootstockInputs` + `LogSink`/`LogSource` + an `AkInputs` wrapper in `rootstock-advantagekit` | `MotorInputs implements LoggableInputs` and writes `LogTable` **directly**. `RootstockInputs`, `LogSink`, `LogSource`, `AkInputs`, `AkBackend` and the `LogBackend` SPI **do not exist**. | §1.1, §3.4, §3.10–§3.12 |
| 22 | ArchUnit rule 1 banned `org.littletonrobotics` outside `rootstock-advantagekit` | Rule 1 no longer names `org.littletonrobotics` or `dev.doglog`. It still bans `com.ctre`, `com.revrobotics`, `org.photonvision`, `com.pathplanner`, `choreo` and `swervelib` outside their adapters. **What replaces it is `DESIGN.md` §8 rule 1c**, a two-clause package allowlist: the *driver* types (`Logger`, `LoggedRobot`, `LoggedNetworkNumber`, `LoggedMechanism2d`) are confined to `org.rootstock.telemetry`/`.core`/`.tuning`/`.viz`, while the two *schema* types (`LogTable`, `LoggableInputs`) are additionally legal in any `..io..` package — which is what makes `MotorInputs implements LoggableInputs` in `org.rootstock.hardware` legal. `org.rootstock.mechanism` still may not touch `Logger`; it publishes through `RootstockLog`. | §1.1, §1.7.1 |
| 23 | `RootstockRobot extends TimedRobot` in core; `RootstockLoggedRobot` in a separate artifact (D13, softened by D29) | **One class: `RootstockRobot extends LoggedRobot`.** `RootstockLifecycle` stays **public** — D29's partial-adoption requirement is untouched and is what makes the collapse clean. | §1.1a, §9.4 |
| 24 | "Zero hard vendordep dependencies in the core artifact… a team can install Rootstock on kickoff day before CTRE and REV have published" | **Withdrawn.** `rootstock` requires `AdvantageKit.json`, a third-party vendordep. The kickoff-morning install property is **lost**, and it was real. | §1.7.6 |
| 25 | Deterministic replay was a property of whichever backend the team installed | Deterministic replay is a **library guarantee**. `Clock`/`RootstockLog.timestamp()` are replay-safe by construction, and `RobotMode.REPLAY` always exists. | §1.1, §7.1 |
| 26 | `@AutoLog` banned because a vendordep cannot install an annotation processor | **D24 stands**, for the *same* reason plus an unresolved package-scope concern. The AdvantageKit dependency does not change it: `RootstockTemplate` can wire the processor for **team** code, but the library still hand-writes `toLog`/`fromLog`. | §1.7.1 |
| 27 | Doc 04 shipped adapters for AdvantageKit, DogLog, Epilogue and a no-op | One path. **A team already committed to DogLog or plain Epilogue cannot adopt Rootstock without switching loggers.** Stated, not buried. | §12 |

**Revision 4 changelog** (2026-08-07 independent expert review — six lenses, synthesized in `REVIEW.md`). Nothing architectural changed. What changed is API truth, arithmetic, and three sections that still described superseded architectures as current.

| # | Was (revision 3) | Now (revision 4) | § |
|---|---|---|---|
| 28 | `MotionMagicVoltage.withPosition(rot).withVelocity(rps)` and the `MotionMagicExpoVoltage` twin | **Neither class has a `Velocity` field or a `withVelocity` method** — re-verified against the CTRE javadoc. The goal-velocity term is folded into the request's **`withFeedForward(volts)`** as `kV_device × goalRps`, and `MotorCapabilities.positionGoalVelocity()` now returns a `VelocityCarrier` enum naming *which* mechanism carries it. | §3.3, §3.5.6, §6.4 |
| 29 | `m_mmDynamic.withVelocity(outputRps).withVelocity(cruise)` — chained twice | `DynamicMotionMagicVoltage.Velocity` **is the cruise velocity** (verified verbatim). The first call is deleted; the goal-velocity term goes through `withFeedForward` on this path too. | §3.5.6 |
| 30 | REV `maxMotion.cruiseVelocity(toOutputRps(v) * 60.0)` | **The `* 60.0` is a 60× error.** MAXMotion parameters are "natively RPM but ... affected by the velocity conversion factor" (verified), and `buildConfig` already sets that factor to produce output rot/s. Both `* 60.0` factors deleted, with the unit derivation shown. | §3.7 |
| 31 | `MotionMagicJerk = constraints().jerk()` (raw user units/s³) | `u.toOutputRps3(...)`, a new `MechanismUnits` method, at both the config and the `DynamicMotionMagicVoltage` site. | §3.5.4, §3.5.6, §4.4, §11 |
| 32 | Elevator `kG = 0.15 V` in a table claiming to *derive* it | **0.33 V**, recomputed from the stated inputs with the cascade ×2 restored, derivation printed in full. Revision 3 dropped the cascade factor. | §5.4, §3.5.7, §9.1 |
| 33 | Arm gear train annotated `34.126:1` in **nine** places `[INTENTIONAL-34.126]` (historical record of the defect — see §4.2's carve-out gate) | **65.411:1** everywhere (58/10 × 58/18 × 42/12), with `rotorPerSensor` and the kG/kV/kA derivations rescaled (kG 0.56 → **0.29 V**, kV 0.65 → **1.25 V/(rad/s)**, kA 0.02 → **0.010**). | §3.10, §4.2, §5.5, §5.6, §9.1 |
| 34 | Sprocket travel from the **pitch-circle circumference** (0.280293 m, printed to six figures) | Chain advance is exactly `teeth × pitch` (**0.279400 m**). `LinearAxis.sprocket` uses `N × p`; `LinearAxis.pulley` already did. Every derived elevator number recomputed. | §4.3, §4.4, §5.4 |
| 35 | "800 frames/s of 8-byte payload ≈ 0.6 % of a 1 Mbps bus" | Off by ~19× (**11.2 %**, not 0.6 %). Recomputed at **~140 bits on the wire per 8-byte extended frame → ≈ 11 %**, with a real aggregate bus budget printed by `describe()` and the 100 Hz default explicitly *provisional* pending the measurement of open question 14. | §3.5.1, §3.5.7 |
| 36 | Homing suspended device soft limits with no specified config path | Homing start/finish is added to `applyVerified`'s exhaustive legal-caller list, with a mandatory read-back-or-abort rule and a sticky alert. | §3.9, §6.3 |
| 37 | `RootstockLifecycle.hooks()` javadoc: "discovered by `ServiceLoader` … Unchanged by decision 3" | **Wrong since decision 3.** In-jar hooks are an explicit priority-ordered list built in `create()`; `ServiceLoader` survives only for out-of-jar vendor adapters and `VisionSimHook` (D26 as amended). | §1.1a |
| 38 | `Mechanism.simulationPeriodic()` + `m_io.simulatedMotorVoltage()` / `updateSimulatedSensors()` call sites | **Deleted by D18.** The sim path is `MotorIO.simHandle() → Optional<SimMotorHandle>`, consumed by `RootstockSim` through `MechanismGeometrySink`. Those two methods never existed on the `MotorIO` interface, so the revision-3 snippet could not have compiled. | §1.4, §6.1, §6.7, §7.1 |
| 39 | §10 item 1: "Two release lines from day one" | The M12-only dual-compile model: one 2026 line through M11, dual-compile scaffolding created **inside M12 and deleted at its end**, one 2027 line after. `ROADMAP.md` §7.2 is authoritative, as docs 02 and 04 already say. | §10 |
| 40 | `Rootstock.alerts()` / `Rootstock.registry()` / `Rootstock.TUNING_MODE` (10 call sites) | **D12 deleted the `Rootstock` god-object.** `Alerts.error/warning(group, text, MatchImpact)` (D10 — the impact argument is mandatory), `org.rootstock.core.RootstockRegistry`, `TuningRegistry.isTuningEnabled()`, `org.rootstock.core.compat.Clock`. Package declarations throughout now match `DESIGN.md` §7. | throughout |
| 41 | `Reduction.ofTeeth(int driving, int driven)` | `Reduction.ofTeeth(int drivenTeeth, int drivingTeeth)`. Every documented call (`ofTeeth(58, 10)` meaning 5.8:1) requires driven-first; the revision-3 parameter names would have inverted every ratio in the library. | §4.2 |
| 42 | `RootstockLog.put(kError, m_goal - m_measured)` *(historical spelling — `put(...)` was deleted in revision 5, see row 42a)* | `kError = setpoint − measured` and a new `kGoalError = goal − measured`, matching `design/04` §3.1's published schema. | §6.2 |
| 42a | *(revision 5, contract reconciliation, review finding B10)* Every `RootstockLog.put(...)` call site in this document | **`RootstockLog.put(...) does not exist`** and never did in `design/04` §2.3, the owning surface. All fifteen CORE call sites now call `critical(...)` (CRITICAL) or `log(...)` (STANDARD) with the tier read off `design/04` §3.1; `timestampSeconds()` is renamed `timestamp()`; the `/Rootstock/Config/Errors` publish becomes a `String[]`. | §1.1, §5.6, §6.2, §8.2 |
| 43 | `RotaryAxis` written out in full without overriding `isContinuous()`; `SiDomain {LINEAR, ANGULAR}` | `isContinuous()` overridden explicitly; `SiDomain {LINEAR_METERS, ROTATIONAL_RADIANS}` per D3. | §4.3 |
| 44 | `hasResetOccurred()` first read from `periodic()` | Consumed once per device at the end of the `MotorIO` constructor, so the power-on flag does not raise a spurious reset alert and start `deviceResetCount` at 1 on every boot. | §3.5.5 |
| 45 | R18's fork tier "license-gated and **[UNVERIFIED]**" | **Verified: AdvantageKit is BSD-3-Clause.** Redistribution and modification are permitted with attribution; only the non-endorsement clause constrains the fork's *naming*. | §1.7.6 |
| 46 | §9: "Every snippet in this section **is** extracted from a compiled, executed test" | Restated in the tense that is true: these are **docs-as-tests targets**; the four named extraction tests are built at **M24** and are release-blocking. See the rewritten §9 preamble. | §9, §11 |
| 47 | *(revision 6, 2026-08-08 — `DESIGN.md` §16 item 5(e), a pre-M1 blocker)* `RootstockRobot(Consumer<LogConfig.Builder>)`, a public no-arg constructor, `@Override public void robotInit()` on `RootstockRobot`, and `RootstockLifecycle.robotInit()` | **D13a/D29 propagated.** `protected RootstockRobot()` / `protected RootstockRobot(LogConfig config)` over an **immutable** `LogConfig` value (no `Consumer`, no `Builder`); `RootstockRobot` overrides `robotPeriodic()`, `disabledInit()` and `close()` **only**; the lifecycle method is `init()`, idempotent, called at the constructor tail or lazily by the first `beforeUserPeriodic()`. `create(LogConfig)`'s "detects an already-started Logger" javadoc is replaced by D29's explicit `defaults()` / `adoptExistingLogger()` choice. Both adoption paths — `extends RootstockRobot` and a team's own `LoggedRobot` — are shown side by side and now differ by exactly one thing. | §1.1a, §5.6, §9.4, §10, §13 OQ15 |
| 48 | *(revision 6)* `LogConfig` was named but never declared in this document | A **reference restatement** of `design/04` §2.2's field set behind D13a's `with*()` copies, plus D29's two factories. ~~The divergence — `design/04` §2.2 is still mutable public fields reached through `RootstockLog.configure(Consumer<LogConfig>)` — is stated inline and filed as **§13 OQ17**, not papered over.~~ **Divergence CLOSED 2026-08-08: `design/04` §2.2b re-declared `LogConfig` as an immutable value with `with*()` copies and D29's two factories, citing D13a. CORE's restatement did not change — it was already written against D13a/D29 — and OQ17 is closed.** The struck-through text is kept because it is the record of a real disagreement that lasted three revisions. | §1.1a, §13 OQ17 |
| 49 | *(revision 6 — `DESIGN.md` §16 item 5(b))* `Mechanism` carried an inline **OPEN CONTRACT ITEM** marker: the `TelemetrySource` **declaration** half was unspecified, so five published keys had no declaration site | **§1.1b writes the declaration half.** `Mechanism implements Subsystem, TelemetrySource` and `Superstructure implements TelemetrySource`; `telemetryName()`, `describe(TelemetryDescriptor)`, the `describeExtras` hook, and all five extras (`DeviceResetCount`, `FeedbackVolts`, `FeedforwardVolts`, `Blocked`, `Plan`) declared with tiers. The descriptor is built **by telemetry** and handed in at `RootstockRegistry.addAll` (D27); CORE never holds one. ~~Two divergences from `design/04` (Degrees-not-Radians; no unit-free `extra(...)`) and the five tiers are filed as **§13 OQ16** rather than assumed.~~ **OQ16 CLOSED 2026-08-08 by `design/04`: Degrees confirmed (its parenthetical was the 57.3× error, corrected there, no conversion added); `extra(String, Tier)` added and CORE's three unit-free extras moved onto it; four tiers confirmed and `DeviceResetCount` overruled to **CRITICAL**, applied here as a matched pair — `d.extra("DeviceResetCount", Tier.CRITICAL)` in §1.1b and `RootstockLog.critical(kDeviceResets, …)` in §6.2.** | §1.1b, §6.1, §8.1, §13 OQ16 |
| 50 | *(revision 6, found while writing row 47 — a **new** finding, not a propagation)* Nothing anywhere noticed that `RootstockLifecycle.create(LogConfig)` puts a `org.rootstock.telemetry` type in a public `org.rootstock.core` signature | **ArchUnit rule 9 exposure, named at the site.** *"Every dependency arrow points into core"* — and a type in a public core signature is the same violation as the direct call §1.1 already discusses, hidden by D28's one-jar packaging rather than fixed by it. Three candidate resolutions are written out; the recommended one is moving `LogConfig` into `org.rootstock.core.spi` (D26's package for downward-crossing value types). ~~**CORE owns neither the type nor the rule and has moved nothing** — filed as **§13 OQ18**, contract request on `design/04` and `DESIGN.md` §8.~~ **RESOLVED AND APPLIED 2026-08-08: `design/04` §2.2b declares `LogConfig` under `package org.rootstock.core.spi;` — option 1, the recommended one. CORE's half is the two `import` lines in §1.1a, both now `org.rootstock.core.spi.LogConfig`; `grep -n "^ *import org\.rootstock\.telemetry\.LogConfig" design/01-core-mechanisms.md` returns **zero**. OQ18 is CLOSED for this document.** Live imports of the old package survive in files this document does not own; **§13 OQ18 states them with an import-shaped gate**, because the bare-string grep counts the prose reporting the fix and so rises as the design gets more correct. | §1.1a, §13 OQ18 |

**Effort delta.** This document has never carried a domain-level person-week total; the authoritative numbers are `ROADMAP.md`'s milestones M1–M6. Decision 3 is **−1.25 pw** across the whole library, of which **about −0.15 pw lands in CORE** — the four `*Inputs` classes now target `LogTable` directly instead of a bespoke sink/source pair, and the cross-backend telemetry-parity test disappears. The larger deletions (`LogBackend`, the four backend implementations, the `RootstockRobot`/`RootstockLoggedRobot` split) belong to the Telemetry and Platform docs. §8.8's 0.3 pw and 0.5 pw sub-item estimates are unaffected.

---

## 0. Scope & Responsibilities

### 0.1 What CORE owns

| # | Responsibility | Deliverable |
|---|---|---|
| 1 | **Hardware seam.** One narrow interface between mechanism logic and motor controllers, encoders, gyros, and digital/ranging sensors. Phoenix 6, REVLib, WPILib-generic, and sim backends. | `org.rootstock.hardware` |
| 2 | **Units, gearing, geometry.** One place where "rotor rotations → meters/degrees → SI" is declared, used by control, soft limits, sim, telemetry, and tuning. | `org.rootstock.units` |
| 3 | **Config system.** Immutable records + fluent builders + collected validation + `describe()` + config snapshot logging + SAFE_MODE. The "change a gear ratio in exactly one place" requirement. | `org.rootstock.config` |
| 4 | **Mechanism templates.** `PositionMechanism`, `VelocityMechanism`, `SimpleMechanism`. Gravity comp, profiling, soft/hard limits, homing, goal/setpoint/measured reporting, `atGoal` semantics. | `org.rootstock.mechanism` |
| 5 | **Subsystem & command idiom.** How a mechanism participates in WPILib command-based *without* forcing it, plus the command-factory vocabulary. | `org.rootstock.mechanism` (`Subsystem` facade) |
| 6 | **Superstructure.** Enum goal/request state machine, interlocks, arm-vs-elevator collision avoidance, default-output inversion, static analysis, measured transition costs. | `org.rootstock.superstructure` |

### 0.2 What CORE explicitly does NOT own

**Domains are named by filename, not by number.** Revision 3 used a nine-domain numbering scheme (`doc 05`, `doc 07`, `doc 08`, `doc 09`) that no longer maps to anything: there are **six** design documents, and `doc 05` in the old scheme meant `design/02-tuning.md`. Every cross-reference below and throughout this revision names the file.

* Swerve drivetrain, odometry, path following — **Drivetrain/Auto**, `design/05-drivetrain-auto.md`. It also owns `org.rootstock.control` (D8), which is where `Gains`, `GravityMode`, `ControlLocation`, `Controllers` and `TuningTarget` live — see §1.5.
* Vision, pose estimation — **Vision**, `design/03-vision.md`.
* The logging facade, replay, dashboards, 3D replay — **Telemetry**, `design/04-telemetry-replay-viz.md`. There is exactly one logging path (AdvantageKit); CORE does not own it and no longer chooses between backends. `design/04` also owns mechanism 3D visualization: CORE *publishes* an articulation angle and a `MechanismGeometry`, and does not own the `Pose3d` math or the AdvantageScope asset contract.
* Tunable numbers, PID tuning UI, the tuning wizard, `FeedbackDesigner` — **Tuning**, `design/02-tuning.md`.
* Autonomous composition, `NamedCommands` — **Auto**, `design/05-drivetrain-auto.md` (same document as Drivetrain).
* Alerts/health monitoring/system check, and the CLI/template — **Platform & Comp Day**, `design/06-platform-compday.md`. CORE *emits* alerts through `Alerts` (D10); it does not define the alert framework.
* Simulation — there is **no separate simulation document**. `RootstockSim` is specified in `design/04` and `design/06`; CORE's side of the seam is `MechanismGeometry` + `MotorIO.simHandle()` (D18, §1.4 and §6.7).

### 0.3 Design stance (non-negotiable, applies to every decision below)

1. **The seam is at goals, never at voltage.** A wrapper that reads a sensor on the RIO, runs a PID on the RIO, and writes a voltage to a Kraken throws away Motion Magic, FOC, 1 kHz on-motor loops, and StatusSignal batching. Rootstock's `MotorIO` sends *positions and velocities in output-shaft rotations* and lets the vendor's controller do the loop. Resolved in full in §1.
2. **Where the loop runs is visible in code, config, diffs, and logs.** `ControlLocation` is an explicit, defaulted-with-provenance, logged, alert-checked field. A team never accidentally moves a loop from a Kraken to the roboRIO.
3. **Every abstraction has a typed escape hatch on page 1.** `elevator.io().as(TalonFXMotorIO.class)` returns the real `TalonFX`. Documented in the first example, not buried.
4. **Zero-mystery debugging.** Every failure names the mechanism, the field, the value, the expected range, and the fix. See §3.6 and §5.6.
5. **Degrade, never crash.** A misconfigured robot **boots**, connects, publishes telemetry, raises alerts, and refuses to move — it does not show red "Robot Code" with a `<clinit>` stack trace. See SAFE_MODE, §5.6.
6. **Sim-first.** Declaring mass/MOI in the config is the *only* thing a team does to get simulation. No second code path.
7. **Config is data; behavior is plain Java.** No JSON, no reflection, no annotation processor of our own.
8. **Nothing is silently frozen.** A value Rootstock did not actually measure is `NaN`, never `0.0`.

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

CORE compiles against these interfaces. Where a domain is genuinely optional, CORE degrades to a no-op (never a crash). **Telemetry is not one of them and never was** — §1.1 has always been marked *required* — and as of maintainer decision 3 its single implementation is AdvantageKit, so "if telemetry is absent" is not a state that can occur.

### 1.1 From Telemetry (`design/04-telemetry-replay-viz.md`) — **required, and AdvantageKit-backed**

> **Revision 3 reversal.** Revision 2 routed every input class through a backend-neutral seam
> (`RootstockInputs` / `LogSink` / `LogSource`) so that AdvantageKit, DogLog, Epilogue and a no-op
> were interchangeable. **Maintainer decision 3 makes AdvantageKit a required dependency**, so
> that indirection now buys nothing and costs an allocation-sensitive wrapper, two parallel
> interfaces, a `Map<String, AkInputs>` cache, and a cross-backend parity test. All of it is
> deleted. `RootstockInputs`, `LogSink`, `LogSource`, `AkInputs`, `AkBackend` and the `LogBackend`
> SPI **do not exist in revision 3.**

CORE lives in the packages `org.rootstock` / `org.rootstock.hardware` / `org.rootstock.mechanism`, published inside the single **`rootstock`** jar whose declared dependencies are **WPILib + AdvantageKit 26.0.2** (D28). Package and source-set boundaries are unchanged and still ArchUnit-enforced — the multi-artifact split stays available at zero cost — but **ArchUnit rule 1 no longer names `org.littletonrobotics`**, so CORE may name `LoggableInputs` and `LogTable` directly. The vendor bans (`com.ctre`, `com.revrobotics`, `org.photonvision`, `com.pathplanner`, `choreo`, `swervelib`) are untouched: those adapters really are separate artifacts and really are optional.

```java
package org.rootstock.telemetry;

import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.Logger;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * The statics CORE writes through (D9). ONE implementation, writing to AdvantageKit's
 * Logger directly. This is a tiered facade, not an abstraction layer: there is no
 * second backend to abstract over, and pretending otherwise is what revision 2 did.
 *
 * Why the facade survives at all, now that there is only one backend behind it:
 *   1. TIERING. The tier IS THE METHOD NAME -- critical(...) / log(...) / debug(...) -- so a
 *      COMPETITION robot does not pay for DEBUG topics. Logger has no tier concept.
 *   2. NAMESPACE. Every CORE key is forced under /Rootstock/, which the demotion governor
 *      (`design/04`) and the byte budget both depend on.
 *   3. ONE CHOKE POINT for the 2027 port. Logger's package moves with AdvantageKit's own
 *      2027 release; RootstockLog is the single file that names it.
 *
 * REFERENCE RESTATEMENT ONLY. `design/04` §2.3 is the SOLE DEFINITION SITE of this static
 * surface (DESIGN.md §5.2 D9 revision 5, and §6's seam table, TelemetrySource row, which says
 * so in as many words: "design/01 §1.1 ... [is a] reference restatement of it"). If this
 * block disagrees with
 * `design/04` §2.3, `design/04` WINS. Only the shapes CORE actually calls are reproduced;
 * the full surface is fifteen value shapes x {critical, log}, each with a trailing-Demotable
 * twin, plus nine supplier-shaped debug forms.
 *
 * THERE IS NO RootstockLog.put(...). It never existed in the owning document. Revisions 1-4 of
 * THIS document declared and called a put(...) family; every one of those call sites was
 * rewritten in revision 5 (contract-reconciliation pass, review finding B10) onto the
 * critical/log surface below. A reappearance of `RootstockLog.put` anywhere is a regression.
 */
public final class RootstockLog {
  /** Delegates to Logger.processInputs. AdvantageKit requires a STABLE LoggableInputs
   *  instance per key across loops; MotorInputs is a long-lived field, so that holds. */
  public static void processInputs(String table, LoggableInputs inputs);

  // ---- CRITICAL tier: always present, including when FMS-attached. CORE's shapes. ----
  public static void critical(String key, double v);
  public static void critical(String key, boolean v);
  public static void critical(String key, String v);
  public static void critical(String key, String[] v);

  // ---- STANDARD tier ("log" is the default tier's name). CORE's shapes. ----
  public static void log(String key, double v);
  public static void log(String key, long v);
  public static void log(String key, boolean v);
  public static void log(String key, String v);
  public static void log(String key, String[] v);

  /** DEBUG-tier gate. Checked by the CALLER so nothing — not even a capturing lambda — is
   *  allocated when the tier is off. See the allocation rule in §1.7.9. */
  public static boolean debugEnabled();

  /** Monotonic loop time. Returns Logger.getTimestamp(); NEVER getFPGATimestamp().
   *  Revision 2 called this "replay-safe by contract, not by comment" and could only
   *  promise it for the AdvantageKit adapter. In revision 3 it is a GUARANTEE, because
   *  there is no other implementation to get it wrong.
   *
   *  NAME NOTE (revision 5). This document spelled it `timestampSeconds()` through revision 4.
   *  `design/04` §2.3 and DESIGN.md D9 both name it `timestamp()`; the owning document wins,
   *  and the old spelling is a compile error, not an alias. */
  public static double timestamp();
}
```

**Unit metadata is not a `RootstockLog` argument at CORE's call sites.** `design/04` §2.3 does carry
`(String, double, Unit)` overloads, but `MechanismUnits` (§4.4) exposes no WPILib `Unit` object —
its user units are a `(Reduction, Axis)` pair, not a `Unit`. CORE therefore calls the plain scalar
shapes and declares each key's unit **once, at registration**, through `TelemetryDescriptor`
(`design/04` §1.1). One declaration per key beats one `Unit` reference pushed 50 times a second.

CORE requires from Telemetry:
* `RootstockLog.timestamp()` to be replay-safe. The `0000-XXXX` template pins this in `Superstructure.java:85-87` and `Vision.java:73-75`; it is now a **library guarantee**, not a per-backend property.
* A **tiered volume** control (`OFF / COMPETITION / FULL / DEBUG`) reusing the already-owned `org.rootstock.core.spi.Tier {CRITICAL, STANDARD, DEBUG}` — CORE does **not** invent a `TelemetryLevel`. *(Package updated 2026-08-08 by the rule-9 resolution: `Tier` moved from `org.rootstock.telemetry` to `org.rootstock.core.spi`. **Telemetry still owns the enum's semantics** — `design/04` §2.2 remains the sole place the three tiers are defined — only the package moved. See §1.1a's resolved blockquote.)*
* `RootstockLog.debugEnabled()` as a plain boolean gate. Doc 04 §2.3 keeps it (and keeps the supplier-shaped `debug(...)` forms); the `debug(String, Supplier<T>, Struct<T>)` shape is **withdrawn for CORE's use**: any supplier that reads instance state (`() -> m_pose`) is a capturing lambda and allocates on every call *even when the tier is off*, which defeats the mechanism's purpose. The gate is checked by the caller.

CORE provides to Telemetry: `MotorInputs implements LoggableInputs` with hand-written `toLog(LogTable)` / `fromLog(LogTable)` (**D24 still stands** — no `@AutoLog`, no `@AutoLogOutput`; see §1.7.1 for why the AdvantageKit dependency does *not* resolve that) — **and the `TelemetrySource` declaration half, specified in §1.1b below.**

**What CORE gains, stated plainly.** Deterministic replay stops being a thing a team can accidentally opt out of. `RobotMode.REPLAY` always exists, `MotorIOFactory` (§7.1) can always return `NoOpMotorIO` for it, and the "mode = REPLAY refusal path" that revision 2 needed — a runtime check that the installed backend could actually replay — is deleted along with the reason for it.

**What CORE loses, stated equally plainly.** A team that has standardised on DogLog or on plain Epilogue cannot adopt Rootstock without switching loggers. That is not a migration guide away; it is a different logging model, a different dashboard workflow, and in Epilogue's case a first-party WPILib feature they were told to prefer. Doc 04 owns the adoption-matrix row that says so; CORE's job is not to imply otherwise anywhere in this document.

**On the `core.spi` hop (D26) — verified before removing, and NOT removed.** Revision 2 introduced `org.rootstock.core.spi.LifecycleHook` + `ServiceLoader` because core calling `RootstockLog.beforeUserPeriodic()` and `TuningRegistry.periodic()` directly, while telemetry and tuning depended on core, **did not compile** across the then-eleven-artifact split. Under D28 those packages now ship in one jar, so the *compile* cycle is gone — the code would build either way. But **ArchUnit rule 9 ("every dependency arrow points into core") is retained**, precisely so the artifact split remains a zero-cost option, and a direct call from core into telemetry violates it. So:

* `core.spi.LifecycleHook`, `VisionSimHook`, `MechanismGeometrySink`, `MechanismGeometry` and `SimMotorHandle` **survive unchanged**. None of them existed for logging-backend optionality.
* What is deleted is the **`LogBackend` ServiceLoader** — backend *discovery* — which existed for optionality and nothing else.
* The number of `META-INF/services` entries CORE cares about drops by one, and the hook that telemetry registers is now a single known implementation rather than "whichever backend was installed". That is the whole of CORE's share of decision 3's savings.

### 1.1a `RootstockRobot` extends `LoggedRobot`, and `RootstockLifecycle` stays **public**

These two facts look like they are in tension. They are not, and the reconciliation is the point.

**Fact 1 (decision 3).** The core artifact depends on AdvantageKit, so there is no longer any reason for two base classes. Revision 1's **D13** — `RootstockRobot extends TimedRobot` in core, `RootstockLoggedRobot extends LoggedRobot` in a separate artifact, with the shared body in a *package-private* `RootstockLifecycle` — collapses to one class. `RootstockLoggedRobot` does not exist.

**Fact 2 (adversarial review, **still binding**).** The review's largest single finding was that "a team can delete Rootstock from one subsystem mid-season without touching the others" was asserted in two places and demonstrated nowhere, because the only object that made partial adoption possible was hidden. **D29** made `RootstockLifecycle` public and required four CI fixture projects. Decision 3 does not touch that finding, does not weaken it, and does not get to use "there is only one base class now" as a reason to re-hide the lifecycle. **`RootstockLifecycle` remains public.**

The resolution is that they were never the same object. `RootstockLifecycle` is *what Rootstock does each loop*; `RootstockRobot` is *one convenient way to get it called*. Decision 3 deletes a second convenience wrapper. It does not delete the seam.

```java
package org.rootstock.core;          // D13 / DESIGN.md §7. NOT org.rootstock.

import java.util.List;
import org.rootstock.core.spi.LifecycleHook;   // D26
import org.rootstock.core.spi.LogConfig;       // core.spi, NOT telemetry -- rule 9. See the
                                                // resolved note under this block, and design/04 §2.2b.

/**
 * PUBLIC. Everything Rootstock needs done per loop, as an object a team can drive by hand
 * from any base class, in any order, from an existing robot they are not willing to rewrite.
 *
 * This is the incremental-adoption seam. 8793 has an existing CommandSwerveDrivetrain and a
 * hand-written Robot; 9143 already extends LoggedRobot with its own Logger setup. Neither
 * team can be asked to change its base class to bolt on the alert registry and the seven
 * health monitors, and M1's whole value proposition is that they do not have to.
 *
 * Nothing here is package-private, and there is a CI fixture (health-only) that uses ONLY
 * this class and never mentions RootstockRobot.
 */
public final class RootstockLifecycle implements AutoCloseable {

  /** THE ONLY FACTORY (D29). There is no no-argument create(); a team that wants the
   *  defaults writes create(LogConfig.defaults()).
   *
   *  WHO STARTS THE LOGGER IS ANSWERED BY THE ARGUMENT, NOT DETECTED.
   *  SUPERSEDED SPELLING, kept as a labelled note because the wrong version of this
   *  javadoc is the interesting part: revision 4 of this document said create() "detects
   *  an already-started Logger and adopts it". DESIGN.md D29 rejects auto-detection --
   *  "a double Logger.start() is a crash, and the two LogConfig factories are how the
   *  design makes 'who starts the Logger' an answered question rather than an assumption."
   *  So the caller states it:
   *      LogConfig.defaults()            -> RootstockLifecycle configures AND starts Logger.
   *      LogConfig.adoptExistingLogger() -> your code already called Logger.start();
   *                                         attach to it and never start it again.
   *  Either way this configures the receivers, the replay source, the write-once pre-start
   *  metadata window and the /Rootstock namespace. */
  public static RootstockLifecycle create(LogConfig config);

  /** D29's init() -- NOT robotInit(). See the supersession note below this block for why
   *  the rename happened and what it cost. Idempotent. Call it at the END of your
   *  constructor, after RootstockRegistry.addAll(...), so the boot dump reflects what you
   *  actually registered; if you never call it, the first beforeUserPeriodic() calls it
   *  for you. Registry validation, config snapshots, describe() dump. */
  public void init();

  public void beforeUserPeriodic(); // inputs, health monitors, tunable poll, tracer epoch open
  public void afterUserPeriodic();  // telemetry flush, budget governor, tracer epoch close
  public void disabledInit();       // the verified full-config re-apply of §3.9 / §9.4
  @Override public void close();

  /**
   * The hooks of D26, AS AMENDED BY MAINTAINER DECISION 3.
   *
   * SURFACE-COUNT NOTE, stated rather than glossed. D29 enumerates this class as "one
   * factory, five instance methods, and no method named robotInit". The five are init(),
   * beforeUserPeriodic(), afterUserPeriodic(), disabledInit() and close(). hooks() is a
   * SIXTH member and it is a read-only diagnostic accessor -- it is what the boot dump and
   * the health-only CI fixture read to answer "which mechanism found this hook". It is
   * declared here because D26 requires the merged view to be inspectable. Whether D29's
   * enumeration is meant to be exhaustive of the whole class or only of the *lifecycle*
   * methods is a one-word clarification owed by DESIGN.md D29, and it is a contract
   * request, not a change this document may make on its own.
   *
   * Revision 3 of this document said these were "discovered by ServiceLoader and ordered by
   * priority(). Unchanged by decision 3." That was wrong, and DESIGN.md D26 says the opposite:
   * decision 3 SIMPLIFIED the dispatch and counted the saving inside its -1.25 pw.
   *
   * The rule, in one line:  OUT-OF-JAR MEANS ServiceLoader; IN-JAR MEANS THE EXPLICIT LIST.
   *
   *   - IN-JAR hooks -- telemetry, tuning, sim, viz -- are registered EXPLICITLY, in code,
   *     inside create(), in a hand-written priority order. D28 already puts them in the same
   *     jar as core, so the compile cycle D26 exists to break is broken by package structure
   *     alone; ServiceLoader was buying nothing and cost a META-INF/services file, a sort, and
   *     a class-loading question at every boot.
   *   - OUT-OF-JAR hooks keep ServiceLoader, because that is where it is load-bearing: the
   *     vendor adapters (phoenix6, revlib, photonvision, pathplanner, choreo, maplesim) exist
   *     precisely so core need not name them. VisionSimHook (D19) stays ServiceLoader-discovered
   *     for the same reason.
   *
   * The returned list is the merged, priority-ordered view of both sources, and the boot dump
   * prints each hook's origin (EXPLICIT or SERVICE_LOADER) so "which mechanism found this hook"
   * is never a guess. ArchUnit rule 9 ("every dependency arrow points into core") is unchanged
   * and is what keeps the artifact split available at zero cost.
   */
  public List<LifecycleHook> hooks();
}
```

> **✅ ArchUnit rule 9 exposure — RESOLVED and APPLIED, 2026-08-08. `LogConfig`, `Tier` and `RobotMode` are all in `org.rootstock.core.spi`.** The import above is legal, and this note is kept because the analysis is the reason the package is where it is; a reader who finds `LogConfig` under `core.spi` and thinks "that is a telemetry type, why is it here" needs the paragraph below, not a clean file.
>
> **AMENDMENT, 2026-08-08 (second half of the same fix — the arrow one level down).** Moving `LogConfig` alone left rule 9 red, and the finding is worth stating in its own words rather than folded into the paragraph below: `LogConfig` lives in `core.spi`, but **its own public signatures named telemetry types** — `Tier mode()`/`minimumTier()` and `RobotMode`-valued members, i.e. `mode()`, `withMode()`, `minimumTier()`, `withMinimumTier()`. A public member of a `core.spi` type whose return type is `org.rootstock.telemetry.Tier` is **the identical arrow out of core, one level down**, and rule 9 counts it identically. **Resolution: `enum Tier` and `enum RobotMode` move to `org.rootstock.core.spi` too** — the same shape, the same argument, the same package D26 created for downward-crossing behaviourless value types, which now holds `MechanismGeometry`, `SimMotorHandle`, `LogConfig`, `Tier` and `RobotMode`. **`Demotable` does NOT move**: it is never named in a core signature, so it has no arrow to fix and moving it would be package churn for nothing. **The narrow named-allowlist alternative was rejected**, and on a stated ground rather than a taste one: an allowlist that grows once grows again, and rule 9's entire value is that it is mechanically checkable with **no judgement calls**. **Telemetry retains OWNERSHIP of both enums' semantics** — `design/04` is still the sole place the three tiers and the three robot modes are *defined*; only the package moved.
>
> **The finding, as it stood.** `RootstockLifecycle` is in **`org.rootstock.core`** (D13a fixes the package) and its only factory takes a **`LogConfig`**, which used to live in **`org.rootstock.telemetry`** (`design/04` §2.2; `DESIGN.md` §10A.4's own import line spelled it `org.rootstock.telemetry.LogConfig`). That is a compile-time arrow **out of core into telemetry**, and rule 9 says *"every dependency arrow points into core"*. §1.1's `core.spi` discussion already states the principle in the call direction — *"a direct call from core into telemetry violates it"* — and a **type in a public core signature is the same violation in a quieter form**: it survives the D28 one-jar packaging exactly as a direct call would, and it is what would break first if the artifact split were ever re-published.
>
> **The three candidate resolutions, kept as the record of why option 1 won.**
> 1. **Move `LogConfig` into `org.rootstock.core.spi`** — the package D26 created for precisely this, *"the types that cross a layer boundary downward"*. `LogConfig` is a pure immutable value with no behaviour, which is the same shape as `MechanismGeometry` and `SimMotorHandle`, which already live there. **← CHOSEN.**
> 2. **Name `LogConfig` in rule 9's allowlist**, the way `DESIGN.md` §8 rule 1c already allowlists `LogTable`/`LoggableInputs` into `..io..` packages. Cheapest to write, and it puts a second named exception into a rule whose value is that it has few. **Rejected on exactly that ground.**
> 3. **Leave it and accept that rule 9 is red on `RootstockLifecycle` from day one.** Rejected on the same ground `design/06` §8.1 rejects it for `volatileApiIsConfined`: *"a rule that is red on day one gets `@Disabled` in week two and the seam becomes decorative."*
>
> **What actually landed, and who landed it.** `design/04` §2.2b now declares `LogConfig` under `package org.rootstock.core.spi;` and states the rule-9 reasoning at the declaration site. **Telemetry still OWNS the field set and every field's semantics** — only the package moved, and `design/04` §2.2b remains the sole definition site of which this document's block below is a reference restatement. **`design/01`'s half is the two `import` lines above**, both now `org.rootstock.core.spi.LogConfig` — plus, after the amendment, **§1.1b's `Tier` import and §7.1's `MotorIOFactory` `RobotMode` import**, four sites in total. That is the whole of it: **no signature changed anywhere in this document**, because `create(LogConfig)`, `RootstockRobot(LogConfig)`, `d.extra(…, Tier.…)` and `create(…, RobotMode mode)` never spelled a package inline. **§13 OQ18 is CLOSED by this.**
>
> **Verification, run rather than asserted (2026-08-08), and written as a grep whose answer does not depend on this paragraph.** `grep -rn "^ *import org\.rootstock\.telemetry\.\(LogConfig\|Tier\|RobotMode\)" design/01-core-mechanisms.md` returns **zero** — no import and no declaration in this document names the old package for any of the three. The only occurrences of those strings here are **prose**, in this blockquote and in §13 OQ18, quoting the dead package in order to say it is dead. In the other direction, both §1.1a import lines, §1.1b's `Tier` import, §7.1's `MotorIOFactory` `RobotMode` import and the restatement block's `package` line all read `org.rootstock.core.spi`.
>
> **Residues, named because "resolved" must not mean "resolved where I could see it."** The import-shaped gate is `grep -rn "^ *import org\.rootstock\.telemetry\.\(LogConfig\|Tier\|RobotMode\)" design/ DESIGN.md`, and **§13 OQ18 carries the current count and the list of any file still failing it**, because that count is driven by parallel passes and a number frozen here would be stale within the hour. The count is *not* recorded in this blockquote for exactly that reason.

```java
package org.rootstock.core;          // D13 / DESIGN.md §7. NOT org.rootstock.

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.littletonrobotics.junction.LoggedRobot;
import org.rootstock.core.spi.LogConfig;    // same package as above, and for the same reason

/**
 * The convenience base class, and the ONLY one. ~20 lines, and every line of it is a
 * delegation to the public RootstockLifecycle above.
 *
 * Revision 2 (D13, softened by D29) had TWO of these: RootstockRobot extends TimedRobot in
 * core, RootstockLoggedRobot extends LoggedRobot in rootstock-advantagekit, kept in sync by
 * hand and by a test. Decision 3 makes AdvantageKit required, so the TimedRobot variant has
 * no consumer and the split has no purpose.
 *
 * A team that does not want this class does not need it. That is the point of D29, and it
 * survives decision 3 completely intact.
 */
public class RootstockRobot extends LoggedRobot {
  private final RootstockLifecycle m_lifecycle;

  /** Defaults. Exactly equivalent to RootstockRobot(LogConfig.defaults()) -- D13a spells the
   *  delegation out, so this constructor is `this(LogConfig.defaults())` and nothing else. */
  protected RootstockRobot() { this(LogConfig.defaults()); }

  /** The configurable form. THE ARGUMENT IS AN IMMUTABLE VALUE, NOT A CONSUMER.
   *
   *  SUPERSEDED SPELLING (revision 5 and earlier of this document): `RootstockRobot(
   *  Consumer<LogConfig.Builder> configure)` with a LogConfig.Builder mutated in place.
   *  DESIGN.md D13a rejects it in as many words -- "a consumer implies a mutable config
   *  object, which principle 6 and D1a forbid everywhere else in the library" -- and
   *  `LogConfig.defaults().withWpilogFolder(..).withCtreSignalLogger(true)` reads the same
   *  and is a value. Both constructors are `protected`: RootstockRobot is a base class, and
   *  the only legal caller of either is a subclass `super(...)` call.
   *
   *  create(LogConfig) is the ONLY RootstockLifecycle factory -- there is no no-arg
   *  RootstockLifecycle.create(). */
  protected RootstockRobot(LogConfig config) {
    m_lifecycle = RootstockLifecycle.create(config);
  }

  // THERE IS NO robotInit() OVERRIDE HERE, and D13a is explicit that there must not be:
  // "robotInit() appears nowhere. RootstockRobot overrides robotPeriodic(), disabledInit() and
  // close() only; all initialization is constructor work." The mechanism that replaces it is
  // RootstockLifecycle.init()'s idempotence: a subclass calls lifecycle().init() at the end of
  // its own constructor (the recommended form, DESIGN.md §10A.4), and if it does not, the
  // first beforeUserPeriodic() below runs it -- which is AFTER the subclass field
  // initialisers have run, so the registry it validates is populated. See the supersession
  // note under this block for the argument this replaces.

  @Override public void robotPeriodic() {
    m_lifecycle.beforeUserPeriodic();          // calls init() on the first cycle if needed
    CommandScheduler.getInstance().run();
    m_lifecycle.afterUserPeriodic();
  }

  @Override public void disabledInit() { m_lifecycle.disabledInit(); }

  @Override public void close() { m_lifecycle.close(); }

  /** Exposed so a subclass can reorder the loop, or call init() explicitly at the end of its
   *  constructor, without giving up the base class. */
  protected final RootstockLifecycle lifecycle() { return m_lifecycle; }
}
```

**`LogConfig` — the shape this document consumes. The divergence that used to be here is gone.**
`design/04` **§2.2b** is the **sole definition site** of `LogConfig`; the block below is a reference
restatement and if the two ever disagree, **`design/04` wins on the field set and on the field
semantics** and **`DESIGN.md` D13a/D29 win on the construction style**. Those are two different
questions and it is still worth saying which document answers which — but as of **2026-08-08 the
two documents agree on both**, which is a change from every earlier revision of this section.
`design/04` §2.2b now declares `LogConfig` as an immutable value with `with*()` copies and exactly
the two D29 factories, in `org.rootstock.core.spi`. **§13 OQ17 is CLOSED outright by that edit. §13 OQ18 is
now CLOSED OUTRIGHT as well** — the `import` lines above were this document's obligation, and the
`design/02` and `design/06` residues OQ18 used to name **were swept in the same pass that moved
`Tier` and `RobotMode`** (2026-08-08). OQ18 keeps the **gate** rather than the count, because a gate
survives the next document that quotes the dead package in prose and a count does not. The
restatement below is what CORE consumes and it did not have to change to match.

```java
package org.rootstock.core.spi;           // `design/04` §2.2b's package as of 2026-08-08.
                                           // NOT org.rootstock.telemetry -- ArchUnit rule 9,
                                           // see the resolved note above. Telemetry still OWNS
                                           // the field set and semantics; only the package moved.

/** REFERENCE RESTATEMENT of `design/04` §2.2b. Every field name below is read off that block:
 *  wpilogFolder, fallbackFolder, compress, ntPublish, minimumTier, perCycleByteBudget,
 *  captureConsole, captureDriverStation, ctreSignalLogger, urcl, minFreeMegabytes,
 *  driverMirror, and the RobotMode `mode`. CORE invents no field.
 *
 *  Construction is D13a's: an IMMUTABLE value with with*() copies, one with*() per field, plus
 *  D29's two factories and NO public constructor. Only the members CORE actually calls are
 *  reproduced. There is no setter, no public field and no Builder.
 *
 *  THE DIVERGENCE THIS COMMENT USED TO CARRY IS RESOLVED, and the resolution went the way this
 *  document assumed. Revisions 6 and earlier recorded that `design/04` §2.2 declared LogConfig
 *  as a class of PUBLIC MUTABLE FIELDS reached through `RootstockLog.configure(Consumer<LogConfig>)`,
 *  and that CORE was written against D13a/D29 instead -- filed as §13 OQ17, deliberately not
 *  papered over. `design/04` §2.2b has since re-declared the type in exactly the shape below,
 *  citing D13a ("principle 6 and D1a forbid mutable config everywhere else in the library") and
 *  D29 (the two factories are how "who calls Logger.start()" becomes an answered question rather
 *  than a double-start crash). The history is kept because a reader who finds a Consumer-shaped
 *  configure() in an old branch needs to know it lost, and why. */
public final class LogConfig {

  /** RootstockLifecycle configures AND starts AdvantageKit's Logger. The from-scratch case. */
  public static LogConfig defaults();

  /** Logger.start() has ALREADY run in the team's own code. Attach; never start it again.
   *  This is the one a team adding RootstockLifecycle to an existing LoggedRobot wants, and
   *  D29 names this document, §11b and the README as the three places that must say so. */
  public static LogConfig adoptExistingLogger();

  public LogConfig withWpilogFolder(String folder);      // `design/04` §2.2b wpilogFolder
  public LogConfig withCtreSignalLogger(boolean on);     // `design/04` §2.2b ctreSignalLogger

  // THE FOUR MEMBERS THAT MADE RULE 9 RED A SECOND TIME, spelled out because "LogConfig moved"
  // was not enough on its own. These name Tier and RobotMode in PUBLIC signatures on a core.spi
  // type; while those two enums lived in org.rootstock.telemetry, this block was an arrow out
  // of core one level down from the one that moved LogConfig. Both enums are now core.spi.
  public Tier      minimumTier();                        // `design/04` §2.2b minimumTier
  public LogConfig withMinimumTier(Tier t);
  public RobotMode mode();                               // `design/04` §2.2b mode
  public LogConfig withMode(RobotMode m);

  // ... one with*() per `design/04` §2.2b field, each returning a NEW instance. Reading is by
  // accessor (wpilogFolder(), minimumTier(), ...). There is no setter and no Builder.
}
```

> **SUPERSEDED, 2026-08-08, by `DESIGN.md` D13a and D29 — recorded, not deleted, because the
> argument it lost to is the one a reader will re-invent.** Revision 4 of this section argued
> that `RootstockRobot` should **keep** an `@Override robotInit()`, on the ground that
> `RootstockLifecycle.robotInit()` must run *after* the subclass's fields exist — a base-class
> constructor runs **before** subclass field initialisers, so a constructor-only design would
> validate an empty registry on every robot. **That premise is correct and it is not what was
> wrong with the design.** What was wrong is that it left `robotInit` as a live method name in
> a library whose own ArchUnit rule 5 bans it, in a document that also shipped two other
> incompatible spellings of the same class (`DESIGN.md` §16 item 5(e)).
>
> **The resolution, which is now the code above.** D29 renames the lifecycle method to
> **`init()`** and makes it **idempotent**; D13a deletes `RootstockRobot.robotInit()` outright.
> Java initialisation order is respected without the hook, by a different mechanism: the
> recommended call site is `lifecycle().init()` at the **end of the subclass constructor**
> (`DESIGN.md` §10A.4), where the subclass's fields provably exist; and if a team forgets, the
> **first `beforeUserPeriodic()`** calls it, which is also after every field initialiser has
> run. Idempotence is what makes both paths safe, and it is why the rename is not cosmetic.
>
> **Consequences of the resolution, so nothing is left dangling:**
> * **The word `robotInit` now appears nowhere in Rootstock's public surface**, so ArchUnit
>   rule 5 needs no exception, and revision 4's request for a *"single documented exception"*
>   in `DESIGN.md` §8 is **withdrawn**. Rule 5 applies to the library without carve-out — which
>   is strictly better than a rule with one blessed violator in the front-door class.
> * A team's **own** `Robot` may still override `robotInit()`; rule 5 governs library code.
>   The partial-adoption snippet below deliberately does **not**, so the two shapes read alike.
> * The 2027 replacement for WPILib's `robotInit` hook is still **[UNVERIFIED]** (whether
>   `LoggedRobot`'s 2027 line exposes an equivalent start callback is not knowable yet), but
>   Rootstock no longer depends on the answer: nothing in the library overrides it. Tracked in
>   §10 item 4 and open question 15, downgraded from a blocker to a compatibility note.

And the partial-adoption path, which is what an existing 8793 or 9143 repo actually does at M1 — no Rootstock base class anywhere:

```java
// frc/robot/Robot.java in an EXISTING repo. This is the health-only CI fixture.
// NOTE THE BASE CLASS: the team's own LoggedRobot (or TimedRobot -- see the honest cost
// below). No Rootstock type is extended anywhere in this file.
public class Robot extends LoggedRobot {
  private final RootstockLifecycle m_rootstock;
  private final RobotContainer m_container;

  public Robot() {
    // adoptExistingLogger(), NOT defaults(): this repo's own code already called
    // Logger.start(). defaults() would start it a SECOND time, which is a crash at boot,
    // not a warning (D29). The choice is not defaulted and is not detected -- see
    // RootstockLifecycle.create()'s javadoc above.
    m_rootstock   = RootstockLifecycle.create(LogConfig.adoptExistingLogger());
    m_container = new RobotContainer();       // ...your existing construction, unchanged...

    // LAST, after everything is registered, so the boot dump reflects what you registered.
    // Idempotent, so forgetting it costs you dump ordering and nothing else: the first
    // beforeUserPeriodic() below runs it (D29).
    m_rootstock.init();
  }

  @Override public void robotPeriodic() {
    m_rootstock.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_rootstock.afterUserPeriodic();
  }
  @Override public void disabledInit()  { m_rootstock.disabledInit(); }
  // There is no robotInit() override in EITHER shape. D13a deleted it from RootstockRobot and
  // D29 renamed the lifecycle method to init(), so the two adoption paths differ by exactly
  // one thing -- which class you extend -- and by nothing else.
}
```

Four CI fixture projects (`tunables-only`, `one-mechanism-only`, `health-only`, `full`) compile on every PR, and **only `full` is permitted to mention `RootstockRobot`**. That rule is what stops the one remaining base class from quietly becoming mandatory again.

**The honest cost of the collapse.** Every fixture above still `extends LoggedRobot`, so partial adoption now presupposes AdvantageKit. A team on plain `TimedRobot` with no logger at all — which revision 2's `RootstockRobot extends TimedRobot` served directly — must adopt AdvantageKit before it can adopt *anything*, including the health monitors that are supposed to be the zero-commitment entry point. `RootstockLifecycle` keeps the seam narrow; it cannot make the dependency optional. This is the sharpest version of decision 3's cost inside CORE, and it is not softened anywhere else in this document.

**One clarification, so this document and `DESIGN.md` D29 do not read as contradicting each other.** D29 says a team may keep `extends TimedRobot` and wire `RootstockLifecycle` by hand; this section says every fixture extends `LoggedRobot`. **Both are true and they mean different things.** A plain `TimedRobot` that calls `Logger.start()` itself *can* drive `RootstockLifecycle` and gets the whole M1 value line — alerts, the seven health monitors, `SelfTest`, `MatchContext`, `RobotIdentity`, `CanIdRegistry`, `ControlMap`. What it does **not** get is deterministic replay, because AdvantageKit's replay driver requires `LoggedRobot` to own the loop and feed it from the log. So `TimedRobot` is a supported but **degraded** shape, the fixtures are written as `LoggedRobot` because that is the recommended shape, and the ranking is `RootstockRobot` → your own `LoggedRobot` → `TimedRobot` with replay given up. Either way the AdvantageKit *dependency* is not avoidable — it is a hard `requires`.

#### 1.1b CORE's half of the `TelemetrySource` contract — the **declaration** half

**Why this section exists.** `design/04` §1.1.0 resolved the push/pull argument in favour of **push**: `sample(TelemetrySink)` and the `TelemetrySink` type are deleted, and every mechanism publishes its own §3.1 key block from its own `periodic()` through the `RootstockLog` statics. Revision 5's reconciliation pass rewrote every CORE call site accordingly — **and stopped there.** `design/04` §1.1 requires a *second* half that push does not supply: `telemetryName()` + `describe(TelemetryDescriptor)`, both consumed exactly once at registration, which is where key units, per-motor array sizes, layout generation and — critically — **the expected key set for the per-cycle schema audit (`design/04` §1.5)** come from. Without it, every key CORE publishes is a key the audit has no declaration for, and the audit is `design/04`'s load-bearing replacement for the deleted pull path. §6.1 carried an inline `OPEN CONTRACT ITEM` marker saying exactly this; **this section is that item, closed** (`DESIGN.md` §16 item 5(b)).

**`design/04` §1.1 is the sole definition site of `TelemetrySource` and `TelemetryDescriptor`. This is CORE's implementation side, and it invents no method.**

```java
package org.rootstock.mechanism;

import static edu.wpi.first.units.Units.*;   // Meters, Degrees, MetersPerSecond,
                                             // DegreesPerSecond, Volts. NOT Value any more --
                                             // `design/04` §1.1's extra(String, Tier) overload
                                             // (added 2026-08-08) removed the last CORE use of
                                             // Units.Value as a stand-in for "no unit".
import org.rootstock.telemetry.TelemetryDescriptor;
import org.rootstock.telemetry.TelemetrySource;
import org.rootstock.core.spi.Tier;          // core.spi, NOT telemetry -- rule 9, same move as
                                              // LogConfig. This class is in ..mechanism.., so the
                                              // import is legal either way; it is spelled core.spi
                                              // because there is now exactly ONE package that
                                              // declares Tier and a second spelling would rot.

/** The two methods `design/04` §1.1 declares, implemented ONCE on the Mechanism base class so
 *  every PositionMechanism / VelocityMechanism / SimpleMechanism inherits them -- which is
 *  literally what `design/04` §1.1 asks for ("TelemetrySource implemented on the mechanism base
 *  classes ... so teams inherit it"). A team writes neither method. */
public abstract class Mechanism implements Subsystem, TelemetrySource {

  /** The constructor's `name` argument, unchanged. It is already the segment every key in
   *  §6.1 is built from ("/Rootstock/" + name + "/"), so "the log key segment" and "the
   *  telemetry name" are the same string by construction and cannot drift.
   *
   *  `design/04` §1.1: "Registering two sources with the same name is a FATAL ConfigError,
   *  not a warning." CORE does not enforce that here -- RootstockRegistry.addAll does, in the
   *  same collected-not-thrown pass as every other config error (§5.6), so a duplicate name
   *  boots into SAFE_MODE with a sentence instead of throwing from a static initializer. */
  @Override public final String telemetryName() { return m_name; }

  /** Called EXACTLY ONCE, from RootstockRegistry.addAll(...) (D27's `instanceof TelemetrySource
   *  -> telemetry` route), and never again. There is no per-cycle callback into this method.
   *
   *  Everything below is read off state the mechanism already has -- the Axis, the MotorGroup,
   *  the mode enum -- so `describe()` cannot disagree with what periodic() publishes unless
   *  the config itself is wrong, which §5.6 already catches. */
  @Override public final void describe(TelemetryDescriptor d) {
    // POSITION AND VELOCITY UNITS. These are the USER units this mechanism publishes, which
    // is what the descriptor must declare -- see the divergence note below.
    switch (m_units.siDomain()) {               // §4.4's accessor; no new method needed
      case LINEAR_METERS      -> d.positionUnit(Meters).velocityUnit(MetersPerSecond);
      case ROTATIONAL_RADIANS -> d.positionUnit(Degrees).velocityUnit(DegreesPerSecond);
    }

    // TWO SMALL CORE-OWNED ACCESSORS ARE DECLARED FOR THIS LINE, and they are named here so
    // the addition is visible rather than assumed:
    //   Mechanism.motorGroup()  -- protected, §6.1. The base class already reads the config
    //                              (its geometry() is `final` and is "computed once from
    //                              PositionConfig + Axis"), so this exposes what is already
    //                              reachable rather than threading a new constructor argument.
    //   MotorGroup.count()      -- == 1 + followers.size(). MotorGroup is CORE's own type
    //                              (§7's package tree, MotorGroup.java), not a vendor type and
    //                              not another document's, so this is ours to add.
    // It sizes §3.1's per-motor arrays and the ./gradlew logBudget estimate, which is the whole
    // reason `design/04` §1.1 asks for a motor count at all. Reading it off MotorInputs'
    // follower arrays instead would be wrong: those are sized by the first updateInputs(), which
    // has not necessarily run when addAll() calls describe().
    d.motorCount(motorGroup().count());
    d.states(MechanismMode.class);              // NEUTRAL|OPEN_LOOP|CLOSED_LOOP|MANUAL|HOMING|
                                                //   CHARACTERIZING -- the enum §6.2 logs as `Mode`

    // ---- The extra(...) keys: every key CORE publishes that is NOT in `design/04` §3.1's
    // ---- fixed Outputs table. Declaring it here is what makes publishing it legal; the §1.5
    // ---- audit rejects any undeclared key under Rootstock/<Name>/, which is the whole point.
    // CRITICAL, not STANDARD, and unit-free. `design/04` §3.1 OVERRULED this document's earlier
    // STANDARD declaration on 2026-08-08: "a motor controller rebooted mid-match" is the textbook
    // case of §2.2's CRITICAL definition ("anything you would need to explain a lost match"), and
    // it is a signal you can only ever read AFTER the match, off a robot that has since been
    // power-cycled -- there is no second copy. STANDARD would in fact survive today's FMS gate
    // (§2.7.1 raises minimumTier to STANDARD and no higher), but minimumTier is a LogConfig field
    // a team can set to CRITICAL at 11pm under a byte-budget squeeze, and §2.2 says CRITICAL keys
    // "are never removed from the schema by any mechanism". THE PAIRED §6.2 EDIT IS RootstockLog.log
    // -> RootstockLog.critical, and the §1.5 schema audit fails loudly if only one of the two moves.
    d.extra("DeviceResetCount",  Tier.CRITICAL);          // §6.2, critical(String, long)
    describeExtras(d);
  }

  /** Subclass hook for the keys only SOME mechanisms publish. Empty by default; the base
   *  class's own extras are declared above so a subclass cannot forget them. */
  protected void describeExtras(TelemetryDescriptor d) {}
}
```

```java
// PositionMechanism (§6.2) -- the RIO_FULL split is published only on that control location,
// but it is DECLARED unconditionally. A conditionally-declared key is a key whose absence the
// audit cannot distinguish from a bug, and `design/04` §1.5's failure mode is exactly
// "a mechanism that silently stops publishing Setpoint is invisible until a student is
// staring at an empty graph at an event".
@Override protected void describeExtras(TelemetryDescriptor d) {
  d.extra("FeedbackVolts",    Volts, Tier.STANDARD);      // §6.2 applyClosedLoop(), RIO_FULL
  d.extra("FeedforwardVolts", Volts, Tier.STANDARD);      // §6.2 applyClosedLoop(), RIO_FULL
}
```

```java
// Superstructure (§8) is a TelemetrySource in its own right: it publishes under its OWN name,
// not under any mechanism's, so it must register and declare like any other source.
public final class Superstructure<S extends Enum<S> & SuperState> implements TelemetrySource {

  /** The literal "Superstructure". NOT a constructor argument, and that is a deliberate,
   *  bounded choice rather than an oversight: `design/04` hard-codes this segment in its own
   *  key literals -- grep it for `Rootstock/Superstructure/State`,
   *  `Rootstock/Superstructure/WhyNotScoring` and `RealOutputs/Rootstock/Superstructure/AtGoal`,
   *  all three written as literals rather than composed from a name -- so a superstructure
   *  that named itself anything else would publish into a namespace nothing reads.
   *  LIMITATION, STATED: this means exactly ONE Superstructure per
   *  robot. Two would collide on the name, which `design/04` §1.1 makes a FATAL ConfigError
   *  at registration -- a loud failure, not a silent overwrite, which is the right failure
   *  mode, but it is still a ceiling. Lifting it is a `design/04` key-literal change plus a
   *  name argument here, and neither is in v0.1. */
  @Override public String telemetryName() { return "Superstructure"; }

  @Override public void describe(TelemetryDescriptor d) {
    d.states(m_stateType);                                  // the team's SuperState enum
    // Unit-free overload (`design/04` §1.1, added 2026-08-08): a String is not dimensionless,
    // it is dimensionless-LESS, and Units.Value was a workaround rather than a declaration.
    // Both tiers CONFIRMED STANDARD by `design/04` §3.1's ruling; both publish via log(...).
    d.extra("Blocked", Tier.STANDARD);                      // §8.2, log(String, String)
    d.extra("Plan",    Tier.STANDARD);                      // §8.2, log(String, String[])
    // No positionUnit/velocityUnit: a superstructure has no single axis. Both are optional
    // on `design/04` §1.1's descriptor -- nothing in that interface requires them.
  }
}
```

**Where the descriptor is built and who hands it over.** Nowhere in CORE. `describe(TelemetryDescriptor)` is a **callback**: `RootstockRegistry.addAll(Object...)` (§5.6, D27) routes each argument by `instanceof`, and the `TelemetrySource` route hands the component to telemetry, which constructs the descriptor implementation, calls `describe(d)` once, and keeps the result. CORE never sees a `TelemetryDescriptor` instance outside the body of its own `describe` method, never stores one, and has no accessor that returns one — which is what keeps the "consumed once, at registration" property in `design/04` §1.1 structurally true rather than conventionally true.

**Two divergences from `design/04`, stated rather than papered over.** ~~**Both are `design/04`'s edits and neither is closed here.**~~ **BOTH RESOLVED 2026-08-08, both in CORE's favour, and both applied above — see the closure note after item 2.** The divergences are kept in the present tense of the revision that found them, because the finding is what made the edit happen and a reader comparing against an older `design/04` needs to recognise which side is which.

1. **A rotary mechanism declares `Degrees`, not `Radians`, and `design/04` §3.1's lead-in says the opposite.** `design/04` §3.1 reads *"the **declared** unit of the mechanism (Meters for an elevator, Radians for an arm)"*. CORE publishes `Goal`/`Setpoint`/`Measured` in **user** units, and for a rotary axis the user unit is **degrees** — `Axis.userPerOutputRotation()` is documented "meters per rot, or **DEGREES** per rot" and `Axis.unitLabel()` returns `"deg"` (§4.3). Radians appear in exactly one place in this library, the SI control path inside `RIO_FULL` (§6.2), and that path's values are never published. **Declaring `Radians` over a stream of degrees is a 57.3× mislabel in AdvantageScope** — precisely the unit-confusion failure `design/02` §3 names as this library's reason to exist. So the descriptor declares what is on the wire. **Contract request on `design/04`: change §3.1's parenthetical to "Meters for an elevator, Degrees for an arm", or state a conversion the publisher must perform.**
2. **`extra(String, Unit, Tier)` has no unit-free overload, and three of the five extras have no unit.** `DeviceResetCount` is a count, `Blocked` is a `String`, `Plan` is a `String[]`. CORE passes `edu.wpi.first.units.Units.Value` — a `DimensionlessUnit`, **verified against the WPILib javadoc** — because it is the only spelling the declared signature admits. It is a workable lie and a small one, but it is a lie: a `String` key is not dimensionless, it is dimensionless-*less*. **Contract request on `design/04`: add `TelemetryDescriptor extra(String key, Tier tier)`** for non-numeric and count-valued keys. Until it exists, `Value` is what CORE writes and this note is why.

> **✅ BOTH DIVERGENCES RESOLVED — `design/04`, 2026-08-08. This is §13 OQ16(a) and OQ16(b) closing, and the code above is already edited to match.**
>
> **(a) Degrees.** `design/04` §3.1's lead-in now reads *"the **declared** unit of the mechanism — **Meters for an elevator, Degrees for an arm**"*, and carries its own paragraph headed *"Degrees, not Radians, for a rotary axis — and this is a correction, not a preference"*, which names the earlier wording a **57.3× mislabel over a degree stream**. **No conversion was added and none is wanted:** CORE keeps publishing what `Axis.unitLabel()` says is on the wire. §1.1b's `ROTATIONAL_RADIANS -> d.positionUnit(Degrees).velocityUnit(DegreesPerSecond)` line is unchanged and is now the agreed spelling rather than the divergent one. *(The `SiDomain` constant is still called `ROTATIONAL_RADIANS` — that names the **internal SI control path**, §6.2's `RIO_FULL` maths, which genuinely is radians and is never published. The constant name and the declared unit are answering different questions and it is worth not "fixing" the one to match the other.)*
>
> **(b) The unit-free overload.** `design/04` §1.1 now declares **`TelemetryDescriptor extra(String key, Tier tier)`** alongside the three-argument form, and §1.1's own note says the two are **not interchangeable**: the three-argument form asserts *"this value is a measurement in this unit"* and writes entry metadata AdvantageScope reads for axis labelling; the two-argument form asserts there is no such unit. **All three unit-free extras above are moved onto it** — `DeviceResetCount`, `Blocked`, `Plan` — and their `Units.Value` arguments are deleted. `FeedbackVolts` and `FeedforwardVolts` keep `Volts` on the three-argument form, because they *are* measurements.

**The tiers of the five extras — `design/04` has now ruled, four confirmed and one overruled. §13 OQ16(c) is CLOSED.** ~~The five extras above are **absent from `design/04` §3.1's table**, so their tiers could not be read off it, and every one is declared `STANDARD` here because that is the tier the existing call site already uses.~~ `design/04` §3.1 added an **Extras** table on 2026-08-08 that rules on all five:

| Extra key | CORE declared | `design/04`'s ruling | Overload | Publish call |
|---|---|---|---|---|
| `DeviceResetCount` | STANDARD | **CRITICAL — OVERRULED** | `extra(String, Tier)` | `RootstockLog.critical(...)` — **changed** |
| `FeedbackVolts` | STANDARD | STANDARD — confirmed | `extra(String, Unit, Tier)`, `Volts` | `RootstockLog.log(...)` — unchanged |
| `FeedforwardVolts` | STANDARD | STANDARD — confirmed | `extra(String, Unit, Tier)`, `Volts` | `RootstockLog.log(...)` — unchanged |
| `Blocked` | STANDARD | STANDARD — confirmed | `extra(String, Tier)` | `RootstockLog.log(...)` — unchanged |
| `Plan` | STANDARD | STANDARD — confirmed | `extra(String, Tier)` | `RootstockLog.log(...)` — unchanged |

**The one overrule and why CORE accepts it without argument.** This document's own note said `DeviceResetCount` at STANDARD was *"arguably wrong"*, and `design/04` agreed and went further. Its reasoning is stronger than the one this document offered: the STANDARD tier **does** survive today's FMS gate — §2.7.1 raises `minimumTier` to STANDARD and no higher, so this document's *"a STANDARD key disappears when the FMS gate raises `minimumTier`"* was itself **wrong on the mechanics** and is corrected here. What actually justifies CRITICAL is that `minimumTier` is a **`LogConfig` field a team can set to `CRITICAL` at 11 pm under a byte-budget squeeze**, and §2.2 promises that *"CRITICAL keys are never removed from the schema by any mechanism"*. A reset counter that can be argued away is a reset counter that will be — and correction row 9 of this document (a setpoint sent once, forever, then silently dropped by a device reset) exists precisely because that failure is invisible unless something counts it.

**Both halves of the overrule land in the same edit, which is not optional.** §1.1b's declaration above is now `d.extra("DeviceResetCount", Tier.CRITICAL)` **and** §6.2's publish is `RootstockLog.critical(...)`. The `design/04` §1.5 schema audit compares declaration against publish and fails loudly if only one moves; that guard is the reason an overrule is safe to accept, and it is also the reason a half-applied one would be worse than none. `DESIGN.md` §16 item 5(b) tracks the pair.

### 1.2 From Tuning (`design/02-tuning.md`) — **required**

```java
package org.rootstock.tuning;

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
* `TuningRegistry.isTuningEnabled()` — a single global. (**D12**: the `Rootstock` god-object that revision 3 called `Rootstock.TUNING_MODE` is deleted; this is its replacement.) When false, `Tunable.get()` returns the compile-time default with **zero** NetworkTables traffic. Team 4738 commented out 30+ `LoggedTunableNumber` declarations by hand rather than ship them to competition (`reefscape2025/.../Vision.java:41-53`, `Elevator.java:47-52`, and 7 commented `LoggedGainConstants` in `Constants.java`). That must never be a choice a team faces.
* Publication under `/Tuning/<MechanismName>/<field>` so AdvantageScope tuning mode and Elastic both work.
* `hasChanged()` hard-gated on `!MatchContext.isFMSAttached()`. (`DESIGN.md` §16 item 3 / domain 06's `onlyMatchReadsDriverStation` rule: `org.rootstock.core.match` is the **only** package permitted to touch `DriverStation`. Revision 3 named `DriverStation` directly here and in three other places in this document; all four now go through `MatchContext`.)
* Values persisted across redeploy (`Preferences`-backed or equivalent).

**What is auto-registered — an explicit allowlist, no reflection.**

Revision 1 said "every numeric field of every mechanism config is auto-registered by field name." That requires reflecting over record components, which contradicts Principle 7 and DESIGN.md §14 row 6, and it exposes a large set of fields where runtime mutation is meaningless or actively harmful. `Mechanism`'s constructor registers exactly this list and nothing else:

```java
// org.rootstock.mechanism.Mechanism, constructor. Hand-written. One line per key.
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

### 1.3 From Alerts & Health (`design/06-platform-compday.md`) — **required**

```java
package org.rootstock.core.alert;    // D10 / DESIGN.md §7. Revision 3 said
                                      // org.rootstock.diagnostics.AlertSink with two-argument
                                      // error/warning -- neither the package nor the arity was right.

/**
 * The alert facade every domain calls (D10). MatchImpact is REQUIRED at every call site:
 * there is no default and no single-argument overload, because "does this stop the robot
 * taking the field?" is the question the driver mirror, the Ready rollup and AlertBudgetTest
 * all depend on, and a default answers it wrongly by omission.
 */
public final class Alerts {
  public static RootstockAlert error(String group, String text, MatchImpact impact);
  public static RootstockAlert warning(String group, String text, MatchImpact impact);
  public static RootstockAlert info(String group, String text);   // INFO is PIT_ONLY by definition
}

public enum MatchImpact { BLOCKS_MATCH, PIT_ONLY }

/** The handle. Owned by domain 06; CORE only calls set()/text(). */
public interface RootstockAlert {
  RootstockAlert set(boolean active);
  RootstockAlert text(String text);
  MatchImpact impact();
}
```

CORE raises, per mechanism, automatically. **Every one of them declares its `MatchImpact` here, once, so no call site in this document has to invent it:**

| Alert key | `MatchImpact` | Why |
|---|---|---|
| `<name>/motor-disconnected` | `BLOCKS_MATCH` | The mechanism cannot move. |
| `<name>/device-reset` | `BLOCKS_MATCH` | A mid-match reset drops the mechanism until we notice. |
| `<name>/follower-disagrees` | `BLOCKS_MATCH` | Two motors fighting destroys gearboxes. |
| `<name>/internal-routing-fault` | `BLOCKS_MATCH` | A Rootstock bug that has latched the mechanism to a no-op. |
| `<name>/unknown-setpoint` | `BLOCKS_MATCH` | A button does nothing; that is a match-losing surprise. |
| `<name>/config-apply-failed` | `BLOCKS_MATCH` | The device is running whatever was on it before. |
| `<name>/absolute-encoder-disconnected` | `BLOCKS_MATCH` | Position is unknown. |
| `<name>/absolute-encoder-disagrees` | `BLOCKS_MATCH` | Position is *plausibly wrong*, which is worse. |
| `<name>/homing-timed-out` | `BLOCKS_MATCH` | `isHomed()` is false, so interlocks refuse to run. |
| `<name>/homing-limits-unrestored` | `BLOCKS_MATCH` | §6.3: device soft limits were not confirmed restored. |
| `<name>/over-temperature` | `PIT_ONLY` | Real, worth fixing between matches, not match-stopping. |
| `<name>/soft-limit-clamped-setpoint` | `PIT_ONLY` | Answers "why won't it go all the way up"; the robot still drives. |
| `<name>/goal-unreachable` | `PIT_ONLY` | A configuration complaint, surfaced in the pit. |
| `<name>/stalled` | `PIT_ONLY` | Transient by nature; the monitors in `design/06` own escalation. |
| `<name>/control-location-downgraded` | `PIT_ONLY` | Boot-time, informational, permanent in `describe()`. |
| `<name>/periodic-threw` | `BLOCKS_MATCH` | One mechanism has taken itself out of service. |

CORE needs from Diagnostics:
* a `SystemCheck` registry so `PositionMechanism` can contribute a canned range-of-motion check;
* a `CanIdRegistry` that CORE scans **once, globally, in `RootstockRegistry.addAll(...)`** — *never* as a side effect of a record constructor (see §5.6b);
* `RootstockTracer.budget(String epoch, Time budget)` so a periodic-time regression is visible rather than inferred from loop overruns.

`9143-2025-A-Updated/src/main/java/frc/robot/Constants.java` documents a CAN ID of 64 that *crashed robot code on boot* because Phoenix IDs stop at 62. That must be a collected, named error that still lets the robot boot into SAFE_MODE — not a stack trace.

### 1.4 From Simulation (`RootstockSim`, specified in `design/04` and `design/06`) — **required**

**D18: CORE declares the plant; Sim owns it.** Revision 3 said CORE "builds the WPILib physics plant itself" and shipped a `Mechanism.simulationPeriodic()` that called `m_io.simulatedMotorVoltage()` and `m_io.updateSimulatedSensors(...)`. **Binding decision D18 deleted all three**, and the two `MotorIO` methods never appeared on the §3.3 interface in the first place, so that snippet could not have compiled. The corrected seam:

* `PositionConfig` + `Axis` produce a **`MechanismGeometry`** value object, declared into the `MechanismGeometrySink` of `org.rootstock.core.spi` (D26). CORE decides *what the plant is* (mass, MOI, effective radius, gearing, limits, gravity) and stops there.
* `MotorIO` gains `default Optional<SimMotorHandle> simHandle() { return Optional.empty(); }` (§3.3). `TalonFXMotorIO` implements it by constructing a `TalonFXSimHandle` **inside the Phoenix adapter**, so the CTRE import never leaves the adapter.
* **`RootstockSim`** — owned by the Simulation domain — builds and steps `ElevatorSim` / `SingleJointedArmSim` / `FlywheelSim` / `DCMotorSim` from the declared geometry, drives each `SimMotorHandle`, and never names a vendor type. There is no `Mechanism.simulationPeriodic()` for a team or the registry to call. See §6.7.

CORE needs from Simulation:
* `Clock.dt()` (`org.rootstock.core.compat.Clock`, DESIGN.md §7) — one authoritative timestep, so `0.02` is never hardcoded again (it is hardcoded in five places across the user's repos). `Clock.seconds()` is the monotonic companion used by the setpoint heartbeat in §3.5.
* `RootstockSim` registering itself as a `LifecycleHook` (D26 — an in-jar hook, so it is on `RootstockLifecycle.create()`'s explicit list, **not** `ServiceLoader`-discovered) and consuming `MotorIO.simHandle()` for every registered mechanism. Fan-out is automatic and is *Sim's* fan-out, not CORE's.
* An optional maple-sim bridge for game-piece interaction. Optional — maple-sim is beta and has a documented succession risk; it must never be a hard dependency. It is genuinely out-of-jar, so it *is* `ServiceLoader`-discovered (D20).

### 1.5 From Drivetrain / Control (`design/05-drivetrain-auto.md`, which owns `org.rootstock.control`)

Drivetrain **consumes** CORE, not the reverse. It uses `Reduction`, `MotorSpec`, `MotorIO`, `Gains`, the validation/`describe()` machinery, and `CurrentLimits`. It does **not** use `PositionMechanism` — swerve steering has its own continuous-wrap + coupling semantics.

Doc 02 also owns `org.rootstock.control`, and CORE depends on exactly one thing from it — the archetype-dispatching feedforward that makes `RIO_FULL` correct:

```java
package org.rootstock.control;

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

### 1.6 From Auto (`design/05-drivetrain-auto.md`)

CORE guarantees to Auto:
* Every mechanism exposes `Trigger atGoal()`, `Trigger atGoal(double toleranceUnits)`, `Trigger stalled()`, `Trigger homed()`.
* Every mechanism exposes `Command goTo(Setpoint)`, `Command hold()`, `Command home()`, `Command neutral()` — safe to register as `NamedCommands` and safe to re-instantiate every enable.
* `Superstructure.request(State)` returns a fresh `Command` each call (WPILib forbids reusing a composed command; `0000-XXXX/RobotContainer.java` already works around this).
* `Superstructure.plannedTransitionSeconds(from, to)` — backed by **measured** costs when `transition_costs.json` exists, and by a conservative profile-time bound otherwise, with the provenance reported (§8.8). `AutoStep.budget()` defaults from it.

Auto owes CORE: a `NamedCommandRegistry` that **rejects duplicate names**. Team 4738 registered `"Stow"` twice and bound `"PrepL4"` to `superstructure.L2` (`reefscape2025/RobotContainer.java:476,497,498`).

### 1.7 Hard constraints inherited from the dossiers

1. **No `@AutoLog`, no `@AutoLogOutput`. D24 stands, and decision 3 does not overturn it.** The obvious reading of "AdvantageKit is now required" is that the annotation processor becomes available and the hand-written `toLog`/`fromLog` can go. It does not, for two reasons that are *independent* of which logger is required:
   * **The vendordep reason, unchanged.** A vendordep cannot add `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$v"` to a consumer's `build.gradle`. Requiring `AdvantageKit.json` puts the akit *runtime* on the consumer's classpath; it does not put an annotation processor into their build. Decision 2 changes this **only for team code**: `RootstockTemplate` owns a `build.gradle` and can wire the processor there — which is exactly what M20's replay-safety lint depends on. It does nothing for a team that adds the vendordep to an existing repo, which is the incremental-adoption path §1.1a exists to serve.
   * **The package-scope reason, unresolved.** `@AutoLog` generates a `MotorInputsAutoLogged` subclass in the *annotated class's* package. CORE's inputs classes are `public` types in `org.rootstock.hardware` that are read by `org.rootstock.mechanism` and written by the vendor adapters in separate artifacts, and the generated subclass — not the annotated class — is the one that must be the field type. That relationship was never worked out, and decision 3 supplies no new information about it.

   So CORE hand-writes `toLog(LogTable)` / `fromLog(LogTable)` on `MotorInputs`. What *has* changed is the target: it is `LoggableInputs` and `LogTable` directly, not revision 2's `RootstockInputs` / `LogSink` / `LogSource`. Revision 2 also claimed this "means CORE works identically for DogLog and Epilogue users" — **that claim is withdrawn**; see §12.
2. **Java 17 syntax ceiling for shared code.** Records ✔, sealed interfaces ✔, `instanceof` patterns ✔, arrow switch ✔. Pattern-matching `switch` ✘ (Java 21), `Math.clamp` ✘ (Java 21).
3. **Immutable `Measure` at config boundaries only; raw doubles in the hot loop.** `MutableMeasure` is *removed* in WPILib 2027 — never expose it.
4. **No removed-in-2027 HAL.** CAN devices and DIO only. No Relay, AnalogOutput, AnalogGyro, SPI (and no SPI IMUs), DMA, Counter, Ultrasonic, AnalogTrigger, interrupts, Servo, digital glitch filter.
5. **No Shuffleboard / SmartDashboard / NT3.** NetworkTables 4 struct publishing only.
6. **~~Zero hard vendordep dependencies in the core artifact.~~ WITHDRAWN by maintainer decision 3.** The `rootstock` artifact depends on **WPILib + AdvantageKit**, and `Rootstock.json` lists `AdvantageKit.json` under `requires`. The old claim — *"a team can install Rootstock on kickoff day before CTRE and REV have published"* — **is no longer true and must not appear anywhere in CORE's docs, examples or README copy.** It was not marketing: AdvantageKit's own 2026 swerve templates shipped weeks late waiting on vendors, which is the fact that made the property worth having, and Rootstock is now on the wrong side of it. What survives is the narrower and still-true rule: **no *vendor* (CTRE/REV/PhotonVision/PathPlanner) dependency in the core artifact.** `rootstock-phoenix6` and `rootstock-revlib` remain separate, optional artifacts, so a REV-only team is never forced to install Phoenix. `rootstock-advantagekit` no longer exists — its contents are in `rootstock`. If AdvantageKit does not publish for a season, Rootstock does not install that season; that is risk **R18, now High and *accepted*, with a three-tier contingency in `ROADMAP.md` §4.2**, numbered here in **execution order**: **Tier 1** — contribute the port upstream; **Tier 2** — fork `rootstock-akit-compat`, ~2.5 pw; **Tier 3** — state publicly, on the README's first screen, that we wait.

> **Tier 2's licence gate is now closed, and it is favourable.** Revision 3 marked it
> **[UNVERIFIED]**. **Verified 2026-08-07: AdvantageKit is released under a BSD 3-Clause
> licence** (`Copyright (c) 2021-2026 Littleton Robotics`,
> <https://github.com/Mechanical-Advantage/AdvantageKit/blob/main/LICENSE>). Redistribution and
> modification, in source and binary form, are permitted with the copyright notice, the condition
> list and the disclaimer retained. **The fork tier is legal.** The one live constraint is the
> non-endorsement clause — *"Neither the name of Littleton Robotics, FRC 6328 ('Mechanical
> Advantage'), AdvantageKit, nor the names of other AdvantageKit contributors may be used to
> endorse or promote products derived from this software without specific prior written
> permission"* — so `rootstock-akit-compat` must not be **named or marketed** as an AdvantageKit
> product; attribution in the licence file is required, endorsement is not implied, and the
> artifact name should be reconsidered before it ships. Leaving a 30-second-checkable licence
> question open on the plan's largest accepted risk was itself a verification failure, and it is
> recorded as one. **Residual:** re-check the licence text against AdvantageKit's 2027 branch when
> that branch exists — it is a different repository state, and this verification does not cover it.
7. **Config apply must be retried and read-back-verified at construction** — and must **never** run on the periodic path. See the two-path split in §3.9.
8. **Never block the main loop on CAN.** `TalonFX.setPosition(double)` blocks up to 100 ms; the library only ever calls `setPosition(value, 0.0)`. `TalonFXConfigurator.apply(config)` blocks with a default 0.050 s timeout; the periodic path only ever calls `apply(config, 0.0)`.
9. **No per-loop allocation in mechanism `periodic()`.** All vendor control-request objects are pre-allocated at construction; **all log keys are precomputed strings**; no capturing lambdas are constructed on the periodic path. Verified in CI by an allocation counter run against the full §9 example robot — **an M5 gate condition** (`ROADMAP.md` §5; the old dated gate G2 is deleted along with G0–G5), not a unit test on a synthetic mechanism.
10. **Nothing is silently frozen.** A signal Rootstock did not subscribe reports `NaN`.

---

## 2. Package layout

**Revision 4 note.** Revision 3's tree put `Rootstock`, `Clock`, `RootstockLifecycle`, `RootstockRobot` and `Reduction` in packages that the binding decisions had already moved, and kept a `Rootstock` god-object that **D12 deleted**. The tree below is the one `DESIGN.md` §7 publishes. Only the packages CORE owns are expanded; everything else is named where it lives so no reader has to guess.

```
org.rootstock.pure                  -- ZERO edu.wpi.first imports (bytecode-scanned)
├── math/  RootstockMath               // clamp, deadband2d, expo, epsilonEquals   [used in §6.2]
└── units/ Reduction.java              // THE gearbox type, positive-only, self-describing   [D7]

org.rootstock.core                  -- WPILib + AdvantageKit only, zero VENDOR deps
├── RootstockLifecycle.java            // PUBLIC (D29). The per-loop seam a team drives by hand.
├── RootstockRobot.java                // extends LoggedRobot. The ONLY base class (§1.1a).  [D13]
├── RootstockRegistry.java             // addAll(), onDisable(), SAFE_MODE entry             [D12/D27]
├── spi/                               // D26. LifecycleHook, VisionSimHook, MechanismGeometrySink,
│                                      // MechanismGeometry, SimMotorHandle. SURVIVES decision 3 --
│                                      // see §1.1. Only the LogBackend ServiceLoader was deleted.
├── compat/  Clock.java                // dt(), seconds() -- the ONLY source of 0.02          [D12]
│            Platform.java             // isSimulation()/isReal(), persistentDir()/deployDir()
├── alert/   Alerts, RootstockAlert, MatchImpact, Severity                                    [D10]
└── match/   MatchContext            // THE only package allowed to read DriverStation

org.rootstock.units
├── Axis.java                        // sealed: LinearAxis | RotaryAxis  (output rot <-> user <-> SI)
├── LinearAxis.java
├── RotaryAxis.java
├── SiDomain.java                    // LINEAR_METERS | ROTATIONAL_RADIANS                  [D3]
├── Range.java
└── MechanismUnits.java              // the ONE conversion object handed to IO, sim, limits, telemetry

org.rootstock.control               -- the canonical control vocabulary (owned by design/05)
├── Gains.java                       // record, named fields only, 7 doubles, VOLTS-PER-SI  [D1/D1a]
├── GravityMode.java                                                                        [D2a]
├── ControlLocation.java, ControlLocationSource.java                                         [D5]
├── Controllers.java                 // the sealed feedforward dispatcher (§1.5)
└── TuningTarget, PlantPrior, TravelLimits, SafetyEnvelope, TuningSupervisor                 [D8]

org.rootstock.config
├── MotorSpec.java                   // sealed: TalonFXSpec | TalonFXSSpec | SparkSpec | GenericSpec | SimSpec
├── OutputMode.java                  // VOLTAGE (shipped) | TORQUE_CURRENT (outside v0.1) -- NOT FOC
├── MotorGroup.java                  // leader + followers + inversion
├── MotorModel.java                  // KRAKEN_X60, KRAKEN_X44, FALCON_500, NEO, NEO_VORTEX, ...
├── MechanismKind.java               // POSITION | VELOCITY | SIMPLE
├── FeedbackSpec.java                // sealed: RotorOnly | FusedCancoder | RemoteCancoder | SparkAbsolute | DioAbsolute
├── MotionConstraints.java           // authoring-time value type, USER units per second^n   [D2]
├── CurrentLimits.java
├── PositionLimits.java
├── ControlConfig.java               // ControlLocation + Gains + constraints + tolerances + gravity
├── SimConfig.java
├── PositionConfig.java              // + PositionConfig.Builder
├── VelocityConfig.java              // + VelocityConfig.Builder
├── SimpleConfig.java                // + SimpleConfig.Builder
├── Setpoint.java                    // named, unit-typed preset + RESOLUTION STATE
├── ConfigError.java                 // a VALUE, not a Throwable subclass in the happy path
├── MechanismConfigSnapshot.java
└── Validation.java                  // localChecks(), crossChecks(), describe(), printAll(),
                                     // toStrings(List<ConfigError>) -> String[] (§5.6)

org.rootstock.hardware
├── MotorIO.java                     // THE seam (+ simHandle(), D18)
├── MotorInputs.java                 // LoggableInputs, hand-written toLog/fromLog (D24)
├── MotorCapabilities.java           // + VelocityCarrier (rev 4, §3.3)
├── AbsoluteEncoderIO.java
├── GyroIO.java
├── DigitalSensorIO.java             // limit switch / beam break / CANrange, unified as level+distance
├── MotorIOFactory.java              // sealed-spec -> IO, one switch
├── phoenix/ *  TalonFXMotorIO, TalonFXSMotorIO, AbstractPhoenixMotorIO, SignalSet,
│              CancoderIO, Pigeon2GyroIO, CanRangeIO, CandiIO, PhoenixUtil, TalonFXSimHandle
├── rev/ *     SparkMotorIO, SparkAbsoluteEncoderIO, RevUtil, SparkSimHandle
├── generic/   GenericMotorIO (RIO-side loop over any WPILib MotorController), DioSensorIO,
│              DioAbsoluteEncoderIO
└── sim/       SimMotorIO, SimGyroIO, SimDigitalSensorIO
                                     // * = ships in a vendor adapter artifact, not in `rootstock`

org.rootstock.mechanism
├── Mechanism.java                   // abstract base, implements Subsystem (not SubsystemBase)
├── PositionMechanism.java
├── VelocityMechanism.java
├── SimpleMechanism.java
├── MechanismMode.java
├── HomingStrategy.java              // sealed: AbsoluteSeed | CurrentSpike | LimitSwitch | AssumeAtBoot | Composite
├── ManualControl.java
└── ContinuousUnwrap.java

org.rootstock.superstructure
├── Superstructure.java
├── SuperState.java                  // interface a team's enum implements
├── AxisGoal.java
├── Interlock.java
├── SafetyModel.java                 // forbidden zones in 2-axis config space
├── TransitionPlanner.java
├── SuperstructureReport.java        // static analysis, §8.8
└── TransitionCosts.java             // measured costs, §8.8
```

**What moved, and under which decision** — so a reader holding revision 3 can diff it:

| Revision 3 said | Revision 4 / `DESIGN.md` §7 | Decision |
|---|---|---|
| `org.rootstock.Rootstock` (`TUNING_MODE`, `registry()`, `alerts()`, `dt()`) | **deleted.** `TuningRegistry.isTuningEnabled()`, `org.rootstock.core.RootstockRegistry`, `org.rootstock.core.alert.Alerts`, `org.rootstock.core.compat.Clock.dt()` | **D12** |
| `org.rootstock.Clock` | `org.rootstock.core.compat.Clock` | D12 |
| `org.rootstock.RootstockLifecycle` / `RootstockRobot` | `org.rootstock.core.*` | **D13**, D29 |
| `org.rootstock.mechanism.RootstockRegistry` | `org.rootstock.core.RootstockRegistry` | D12, **D27** |
| `org.rootstock.config.Gains` | `org.rootstock.control.Gains` | **D1** |
| `org.rootstock.units.Reduction` | `org.rootstock.pure.units.Reduction` | **D7** |
| `org.rootstock.hardware.ControlLocation`, `units.GravityMode` | `org.rootstock.control.*` | D2a, **D5** |
| `SiDomain {LINEAR, ANGULAR}` | `SiDomain {LINEAR_METERS, ROTATIONAL_RADIANS}` | **D3** |
| `org.rootstock.diagnostics.AlertSink` | `org.rootstock.core.alert.Alerts` (+ mandatory `MatchImpact`) | **D10** |

Code blocks below carry the corrected `package` line. Where a snippet imports one of these types, the import is the corrected one.

---


## 3. Brief item 1 — Hardware abstraction layer

### 3.1 The tension, stated precisely

A naive `RootstockMotor` looks like this and is **wrong**:

```java
// WHAT WE DO NOT BUILD
interface RootstockMotor {
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
package org.rootstock.hardware;

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
   *  Re-sent every loop. On a TalonFX this is a DOWNGRADE and Rootstock says so. */
  RIO_FULL
}
```

A team choosing `RIO_FULL` on a Kraken sees, in the driver station and in the log:

```
[Rootstock][WARN] Elevator: ControlLocation.RIO_FULL on a TalonFX (CAN 20) disables Motion Magic
  and the 1 kHz on-motor loop. The mechanism will run a 50 Hz roboRIO loop instead.
  If this is intentional, ignore. If not, use ControlLocation.ON_MOTOR_PROFILED.
```

If a team asks for `ON_MOTOR_PROFILED` on a backend that cannot do it (`GenericSpec`), CORE **downgrades and says so** rather than silently misbehaving:

```
[Rootstock][ERROR] Wrist: ControlLocation.ON_MOTOR_PROFILED requested, but MotorSpec.Generic
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
public TalonFXConfiguration configuration();     // the live config object Rootstock built
public void applyRaw(Consumer<TalonFXConfiguration> mutator);  // merge-and-reapply, retried+verified
```

Usage, and it appears in the README's *first* elevator example, not an appendix:

```java
// Anything Rootstock does not model, you do yourself, on the real device.
elevator.io().as(TalonFXMotorIO.class).ifPresent(io -> {
    io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false);
    io.talonFX().setControl(new MusicTone(440));
});
```

`applyRaw` uses the **verified** path (§3.9) and is documented as a construction-time / on-demand call, not a periodic one.

### 3.3 `MotorIO` — the seam, in full

```java
package org.rootstock.hardware;

import org.rootstock.control.Gains;
import org.rootstock.config.MotionConstraints;
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
   *
   *                                 THE SEAM PROMISES DELIVERY, NOT A MECHANISM. Not every
   *                                 vendor request has a velocity field -- Phoenix's Motion
   *                                 Magic requests do NOT (verified, §3.5.6) -- so a backend
   *                                 declares HOW it carries the term via
   *                                 capabilities().positionGoalVelocity(), and every value of
   *                                 that enum except UNSUPPORTED delivers it. A backend may
   *                                 never silently drop it; that is the defect this parameter
   *                                 exists to make impossible, and a backend that cannot carry
   *                                 it must return UNSUPPORTED so PositionMechanism folds the
   *                                 term into an RIO-side voltage trim instead (§6.4).
   * @param arbFeedforwardVolts      ADDED on top of whatever gravity term the controller
   *                                 computes from Gains.kG. Pass 0 for on-motor gravity.
   */
  void setPositionGoal(double outputRotations, double outputRotationsPerSecond,
                       double arbFeedforwardVolts);

  /**
   * Same, but with a one-request constraint override -- the runtime constraint-profile path
   * (§6.2 addConstraintProfile). Backends that cannot do this per-request return
   * capabilities().dynamicProfile() == false and Rootstock routes the mechanism to
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

  // ---- simulation (D18) ------------------------------------------------
  /** The sim seam. CORE DECLARES the plant (MechanismGeometry); RootstockSim OWNS and steps it.
   *  A backend that can be driven in simulation returns a handle here; RootstockSim writes the
   *  simulated rotor state through it and reads the applied voltage back. Empty means "this
   *  backend has no simulation", and RootstockSim says so at boot rather than silently doing
   *  nothing -- the NEO-has-no-sim gap in 0000-XXXX-Robot-Template made real.
   *
   *  This method REPLACES revision 3's Mechanism.simulationPeriodic() plus the
   *  simulatedMotorVoltage() / updateSimulatedSensors() pair, which D18 deleted outright and
   *  which never appeared on this interface. See §6.7. */
  default Optional<SimMotorHandle> simHandle() { return Optional.empty(); }

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
    VelocityCarrier positionGoalVelocity,   // HOW a velocity rides along with a position goal
    boolean torqueCurrentControl,    // Phoenix FOC torque-current  (OutputMode.TORQUE_CURRENT)
    boolean fusedAbsoluteEncoder,
    boolean readsTorqueCurrent,
    boolean readsTemperature,
    boolean reportsDeviceReset,
    boolean reportsConnectionHealth) { }
```

```java
/**
 * HOW the goal-velocity argument of setPositionGoal(...) actually reaches the motor.
 *
 * Revision 3 made this a boolean, which turned out to be the wrong shape and hid a real defect:
 * the flagship Phoenix path claimed `true` while calling MotionMagicVoltage.withVelocity(...),
 * a method that DOES NOT EXIST (verified -- §3.5.6). A boolean can only say "yes" or "no"; the
 * honest answer for Motion Magic is "yes, but through a different mechanism, with a different
 * failure mode, and describe() had better print which one."
 *
 * PositionMechanism does not branch on this value -- it always passes the term to the seam.
 * The backend chooses the carrier. The value exists so describe(), the self-test, and
 * FieldLockedTurretParityTest can all name the path in use.
 */
public enum VelocityCarrier {
  /** The vendor request has a real velocity REFERENCE field, and the device's own closed loop
   *  consumes it. Phoenix PositionVoltage.Velocity -- "Velocity to drive toward in rotations
   *  per second. This is typically used for motion profiles generated by the robot program."
   *  (VERIFIED, Phoenix 6 javadoc.) Highest fidelity: the term participates in the device's
   *  error computation, not just its output sum. */
  NATIVE_REQUEST_FIELD,

  /** No velocity field on the request, so the term is converted to VOLTS on the RIO and added
   *  to the request's arbitrary-feedforward field: ff += kV_device * goalRps.
   *  kV_device is exactly the Slot0.kV this library already computed in §3.5.4 step 6
   *  (kV_si * siPerOutputRotation), so the conversion introduces no new number and no new
   *  place for a unit error. This is the Motion Magic path -- MotionMagicVoltage,
   *  MotionMagicExpoVoltage and DynamicMotionMagicVoltage all have FeedForward and none has
   *  Velocity (VERIFIED). Slightly lower fidelity than NATIVE_REQUEST_FIELD: the device's
   *  velocity error term does not see the offset, so steady-state tracking relies on the
   *  position loop plus this open-loop volt term. For the field-locked turret that is the
   *  correct decomposition anyway (§6.4). */
  DEVICE_FEEDFORWARD_VOLTS,

  /** The backend has neither a velocity field nor an arbitrary-feedforward field on its
   *  position request. PositionMechanism folds the term into an RIO-side voltage trim before
   *  the seam call. Reported, logged, and NEVER silently dropped. */
  RIO_VOLTAGE_TRIM,

  /** The backend genuinely cannot honour a nonzero goal velocity at all. Setting one raises a
   *  FATAL ConfigError at construction if the mechanism declares RotaryAxis.fieldLocked(...),
   *  because a field-locked turret on such a backend is a feature that does not work. No
   *  shipped backend returns this; it exists so a future one cannot pretend. */
  UNSUPPORTED
}
```

### 3.4 `MotorInputs` — hand-written `LoggableInputs`

```java
package org.rootstock.hardware;

import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * Everything read FROM a motor, once per loop, in OUTPUT-SHAFT units.
 *
 * Implements AdvantageKit's LoggableInputs DIRECTLY (revision 3, maintainer decision 3).
 * Revision 2 implemented a backend-neutral RootstockInputs and let an AkInputs wrapper adapt
 * it, because ArchUnit rule 1 then banned org.littletonrobotics outside a separate adapter
 * artifact. AdvantageKit is now a required dependency, rule 1 no longer names it, and the
 * wrapper -- plus its per-key instance cache -- is deleted.
 *
 * toLog/fromLog are still HAND-WRITTEN (D24: no @AutoLog, no @AutoLogOutput). The reason is
 * NOT which logger is required; it is that a vendordep cannot install an annotation processor
 * into a consumer build, and that @AutoLog's generated-subclass package scope was never
 * resolved for a public type read across package boundaries. See §1.7.1.
 *
 * The field names below are the LOG SCHEMA. Changing one breaks replay of older logs, so
 * they are frozen at the API freeze (M24) and asserted by a schema test.
 *
 * NaN DISCIPLINE: every double field backed by a StatusSignal this IO did not subscribe is
 * stamped Double.NaN ONCE at construction and never written again. A frozen zero is a lie;
 * NaN is honest, and it is visibly wrong on an AdvantageScope plot.
 */
public class MotorInputs implements LoggableInputs {
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

  @Override public void toLog(LogTable t) {
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

  @Override public void fromLog(LogTable t) {
    connected             = t.get("Connected", connected);
    positionRot           = t.get("PositionRot", positionRot);
    velocityRps           = t.get("VelocityRps", velocityRps);
    appliedVolts          = t.get("AppliedVolts", appliedVolts);
    supplyCurrentAmps     = t.get("SupplyCurrentAmps", supplyCurrentAmps);
    statorCurrentAmps     = t.get("StatorCurrentAmps", statorCurrentAmps);
    torqueCurrentAmps     = t.get("TorqueCurrentAmps", torqueCurrentAmps);
    temperatureCelsius    = t.get("TemperatureCelsius", temperatureCelsius);
    forwardLimitTripped   = t.get("ForwardLimitTripped", forwardLimitTripped);
    forwardLimitValid     = t.get("ForwardLimitValid", forwardLimitValid);
    reverseLimitTripped   = t.get("ReverseLimitTripped", reverseLimitTripped);
    reverseLimitValid     = t.get("ReverseLimitValid", reverseLimitValid);
    closedLoopReferenceRot= t.get("ClosedLoopReferenceRot", closedLoopReferenceRot);
    deviceResetCount      = t.get("DeviceResetCount", deviceResetCount);
    followerPositionRot   = t.get("FollowerPositionRot", followerPositionRot);
    followerStatorAmps    = t.get("FollowerStatorAmps", followerStatorAmps);
    followerTemperatureC  = t.get("FollowerTemperatureC", followerTemperatureC);
    followerConnected     = t.get("FollowerConnected", followerConnected);
  }
}
```

`LogTable.put(String, T)` and `LogTable.get(String, T defaultValue)` are overloaded per type, so the two methods above read as one line per field with no per-type accessor names — which is a small, real readability win over revision 2's `LogSink`/`LogSource` pair. **[UNVERIFIED]** whether AdvantageKit 26.0.2 spells the read accessor `get(String, T)` for every one of these types or retains type-suffixed variants for some; the implementer confirms against the `LogTable` javadoc before writing the four inputs classes. This is a mechanical detail, not a design question — but it is exactly the kind of thing revision 2's `LogSink` abstraction hid, and hiding it is no longer worth an artifact.

### 3.5 Backend: Phoenix 6 — `TalonFXMotorIO`

This is where 900 lines of the user's template collapse to one class.

#### 3.5.1 `SignalSet` — subscription and read are ONE declaration

Revision 1 shipped, inside the library, byte-for-byte the bug it was written to prevent:
`configureSignals` subscribed `m_supplyCurrent` and `m_closedLoopReference` only `if (level.atLeast(FULL))`, never subscribed the two limit signals at all, then called `optimizeBusUtilization()` — which sets every unrequested signal to 0 Hz. `updateInputs` then read all four unconditionally. Below `FULL` telemetry those four fields would be frozen at their power-on values forever, with no error. That is exactly `8793-2026-Robot/.../ShooterSubsystem.java:180,185,189`, and exactly what `design/02` §7.2 abort condition 11 exists to detect. It also made `SensorSpec.motorLimit(...)`'s advertised *"zero extra CAN traffic — the motor already reports it"* permanently false.

The fix is structural, not a second `if`:

```java
package org.rootstock.hardware.phoenix;

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
  // Supply current is NOT optional either: it feeds the brownout and power monitors (design/06).
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

**CAN budget — recomputed in revision 4, because revision 3's number was wrong by roughly 19×.**

Revision 3 said: *"Raising position+velocity from 50 to 100 Hz costs 2 extra frames per motor per 20 ms. On the eight motors of the §9 robot that is 800 frames/s of 8-byte payload ≈ 0.6 % of a 1 Mbps `rio` bus and immaterial."* **The 0.6 % treated the 8-byte payload as 8 *bits*.** It is 64 bits of payload inside a frame that is more than twice that size on the wire, and that false number was the entire justification for defaulting every mechanism to 100 Hz. Here is the arithmetic, in full, so it can be checked.

**Step 1 — how many bits a frame costs.** Phoenix 6 uses 29-bit extended identifiers. A CAN 2.0B extended data frame carrying 8 bytes is:

```
  SOF                        1
  identifier (11 + 18)      29
  SRR + IDE + RTR            3
  reserved r1, r0            2
  DLC                        4
  data                      64
  CRC                       15
  CRC delimiter              1
  ACK slot + delimiter       2
  EOF                        7
  inter-frame space          3
                          ----
  nominal                  131 bits
```

Bit stuffing applies to the 118 bits from SOF through CRC: worst case adds `floor((118-1)/4) = 29` stuff bits (160 total), and for the mixed sensor data these frames actually carry the expected addition is closer to **8**. **The planning figure used throughout this document is 140 bits per 8-byte extended frame** (131 nominal + 9); the honest range is 131–160, so every percentage below carries about ±7 % of its own value.

**Step 2 — the increment revision 3 mispriced.** Raising position + velocity from 50 Hz to 100 Hz adds 2 frames per motor per 20 ms = **100 extra frames/s per motor**:

```
  8 motors x 100 extra frames/s x 140 bits = 112,000 bit/s = 11.2 % of a 1 Mbps `rio` bus
```

Not 0.6 %. Payload-only it is still 5.1 %, so even the most charitable reading of revision 3's sentence is off by 8×.

**Step 3 — the whole per-motor budget, not just the increment.** A `POSITION` mechanism at the defaults above subscribes:

| Channel | Rate | Frames/s |
|---|---|---|
| `POSITION` | 100 Hz | 100 |
| `VELOCITY` | 100 Hz | 100 |
| `APPLIED_VOLTS` | 50 Hz | 50 |
| `STATOR` | 50 Hz | 50 |
| `SUPPLY` | 20 Hz | 20 |
| `TEMPERATURE` | 4 Hz | 4 |
| **total** | | **324 frames/s** |

`324 × 140 = 45,360 bit/s` = **4.5 % of a 1 Mbps bus, per position-controlled motor.** The §9 robot's three TalonFX motors are therefore **13.6 %** (972 frames/s, 136 kbit/s) before the Spark roller, the CANcoder or the CANrange.

**This is an UPPER BOUND, and the reason it is an upper bound matters.** Phoenix 6 co-locates several signals in one device status frame, and `setUpdateFrequency` on any signal in a frame raises the *frame's* rate — so subscribing `POSITION` and `VELOCITY` at 100 Hz may cost 100 frames/s rather than 200 if they share a frame. **Which Phoenix 6 signals share which status frame is [UNVERIFIED]** — CTRE does not document the packing, and guessing it is exactly the sort of thing this document is not allowed to do. The one-frame-per-signal bound is what `describe()` prints, it is what open question 14's measurement must be compared against, and it is stated as a bound rather than an estimate.

**Consequence for the default, stated plainly.** A swerve drivetrain on the same `rio` bus routinely runs 60–80 % utilisation on its own. At 4.5 % per mechanism motor, **eight mechanism motors at these defaults can push a loaded bus past saturation** — which manifests as dropped frames and frozen signals, the precise failure this library exists to prevent. Therefore, changed in revision 4:

1. **100 Hz is the default only for `MechanismKind.POSITION`.** `VELOCITY` mechanisms already excluded position (§6.5); they now also default `VELOCITY` to **50 Hz**, because a flywheel's `atGoal` gate is debounced over tens of milliseconds and gains nothing from 100 Hz. `SIMPLE` mechanisms subscribe neither.
2. **`describe()` prints a true aggregate frame budget**, per bus, summed over every registered mechanism, plus a declared allowance for a drivetrain (`RootstockRegistry.declareBusAllowance("rio", 0.65)` — the drivetrain domain sets it; the default when nothing declares one is 0, and `describe()` says so rather than assuming).
3. **A Tier-2 alert fires when the computed total for a bus exceeds 60 %**, naming every contributor and the per-mechanism `signalRateHz(...)` override that fixes it.
4. **The 100 Hz figure is provisional and labelled as such.** Open question 14 requires a measurement of 8793's actual bus before it is frozen. The rationale for 100 Hz — a 50 Hz signal sampled by a 50 Hz loop aliases and can add a full 20 ms of latency — is unchanged and is still right; what was wrong was the claim that it was free.

`MotorSpec.talonFX(id, bus).signalRateHz(50.0)` exists for a team that measures a problem, and CAN FD (SystemCore) changes these numbers substantially in our favour — but Rootstock must be correct on a 1 Mbps `rio` bus first, because that is what the target teams have.

#### 3.5.2 Fields

```java
package org.rootstock.hardware.phoenix;

public final class TalonFXMotorIO implements MotorIO {
  private final TalonFX   m_leader;
  private final TalonFX[] m_followers;
  private final TalonFXConfiguration m_config = new TalonFXConfiguration();
  private final ControlLocation m_location;
  private final MechanismUnits  m_units;
  private final String          m_name;

  /** FOC and OUTPUT MODE are ORTHOGONAL. See "FOC vs output mode" below. */
  private final boolean    m_foc;          // -> ControlRequest.withEnableFOC(...) only
  private final OutputMode m_outputMode;   // VOLTAGE (shipped) | TORQUE_CURRENT (post-v0.1)
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

Revision 1 conflated them, and the flagship example broke as a result: `DESIGN.md §10.1`'s elevator used `MotorSpec.talonFX(20,"rio").foc(true)` together with `Gains...withFeedforward(0.15, 0.62, 0.03)` — volts-per-SI numbers that revision 1 would have silently reinterpreted as amps-per-SI. `kV = 0.62 A/(m/s)` is meaningless. Worse, `design/02`'s `GainSink` conversion table has no torque-current row and `FeedbackDesigner` returns kP in V/m, so the tuning wizard, the LQR designer and the persisted `gains.json` could not serve the library's own recommended elevator config.

```java
/** How the device turns a closed-loop output into a command. NOT the same as FOC. */
public enum OutputMode {
  /** MotionMagicVoltage / PositionVoltage / VoltageOut. Gains are VOLTS-per-SI. The shipped default. */
  VOLTAGE,
  /** MotionMagicTorqueCurrentFOC / PositionTorqueCurrentFOC / TorqueCurrentFOC.
   *  Gains are AMPS-per-SI -- a DIFFERENT number for every gain. NOT in v0.1. */
  TORQUE_CURRENT
}
```

* `MotorSpec.talonFX(id, bus).foc(boolean)` sets **only** `ControlRequest.withEnableFOC(...)` on the voltage requests. It does not touch gain units, does not change which request objects are used, and is `true` by default on a Pro-licensed device (`MotionMagicVoltage.EnableFOC` already defaults true). Keeping `.foc(true)` with volt gains — as `DESIGN.md §10.1` and §9.1 below do — is correct and stays.
* `MotorSpec.talonFX(id, bus).outputMode(OutputMode.TORQUE_CURRENT)` is the separate, explicitly named field. **It raises a `ConfigError`** naming the reason:

```
org.rootstock.config.ConfigError: Rootstock config error in "Elevator" (PositionConfig)

  field    motors.leader.outputMode = TORQUE_CURRENT
  expected VOLTAGE  (the only supported output mode)

  Rootstock gains are VOLTS-per-SI (V/m, V/(m/s), V). Torque-current gains are AMPS-per-SI,
  which is a different number for every one of kP kI kD kS kV kA kG, and the tuning wizard,
  the LQR feedback designer and the persisted gains.json cannot yet convert between them.
  Shipping TORQUE_CURRENT now would mean your tuned gains are silently wrong by the
  motor's torque constant.

  If you want FOC's extra torque today, that is a DIFFERENT switch and it is already on:
      MotorSpec.talonFX(20, "rio").foc(true)          // enables FOC on the voltage requests
  Torque-current output mode is not in v0.1. See design/01 open question 13.
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
  //    here, and ONLY here. This is the same conversion `design/02` §4.2's GainSink performs.
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

  // Required by `design/02` §4.2 and omitted by rev 1: kS must oppose the CLOSED-LOOP direction,
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

  // 7. PROFILE, in OUTPUT rotations/s, /s^2 and /s^3.
  //    ALL THREE are converted. Revision 3 converted cruise and acceleration and then passed
  //    JERK RAW -- MotionConstraints.jerk is in USER units/s^3 (deg/s^3 or m/s^3) and
  //    MotionMagicJerk is rot/s^3, so a rotary axis was off by 360 and a linear axis by
  //    1/(travel per output rotation). That is the inlined-conversion bug class §4.1 says
  //    MechanismUnits exists to make impossible, sitting inside this library's own flagship
  //    config builder. u.toOutputRps3(...) is the missing method; see §4.4 and UnitsContractTest.
  m_config.MotionMagic.MotionMagicCruiseVelocity =
      u.toOutputRps(c.control().constraints().maxVelocity());
  m_config.MotionMagic.MotionMagicAcceleration   =
      u.toOutputRps2(c.control().constraints().maxAcceleration());
  m_config.MotionMagic.MotionMagicJerk           =
      u.toOutputRps3(c.control().constraints().jerk());

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
> `* u.siPerOutputRotation()`, which is what the same finding's companion item and `design/02`
> §4.2's `GainSink` actually use. Verified against
> [MotionMagicConfigs (Phoenix 6 26.x)](https://api.ctr-electronics.com/phoenix6/latest/cpp/classctre_1_1phoenix6_1_1configs_1_1_motion_magic_configs.html):
> `MotionMagicExpo_kV` default 0.12 V/rps, range 0.001–100; `MotionMagicExpo_kA` default
> 0.1 V/rps², range 1e-05–100; `MotionMagicCruiseVelocity` — *"When using Motion Magic Expo
> control modes, setting this to 0 will allow the profile to run to the max possible velocity
> based on Expo_kV."*

**Two new Tier-1 validation rules fall directly out of step 6 and 7b:**

```
org.rootstock.config.ConfigError: Rootstock config error in "Arm" (PositionConfig)

  field    axis.horizontalAt = 95.0 deg  ->  0.2639 output rotations
  expected within +/-0.25 rotations (+/-90 deg) of the mechanism zero

  Phoenix Slot0Configs.GravityArmPositionOffset accepts only (-0.25, 0.25) rot. A larger
  offset is clamped by the device with NO error, so kG would be applied at the wrong angle
  over the whole range and the arm would sag on one side and slam on the other.

  Fix: re-zero the CANcoder magnet offset so the encoder reads NEAR 0 with the arm level,
  then set RotaryAxis.arm(Degrees.of(0)). Capture the new offset with `rootstock zero Arm`.
  (If your encoder physically cannot be re-zeroed, set ControlLocation.RIO_FULL -- WPILib's
  ArmFeedforward has no offset limit -- and Rootstock will tell you it downgraded.)
```

```
[Rootstock][WARN] Elevator: control.useExpo() is true but gains.kV = 0.00 and gains.kA = 0.00.
  Motion Magic Expo shapes its profile ENTIRELY from measured kV and kA; with them at zero
  Rootstock would hand the device CTRE's factory defaults (0.12 V/rps, 0.1 V/rps^2) and the
  profile would have nothing to do with your mechanism.
  Fix: run the tuning wizard's kV/kA steps (`rootstock tune Elevator --feedforward`), or switch
  to ControlLocation.ON_MOTOR_PROFILED with trapezoid constraints (useExpo(false)).
```

#### 3.5.5 Reads — one batched transaction, plus device-reset detection

**The boot flag is consumed in the constructor, not counted as a reset.** `ParentDevice.hasResetOccurred()` is documented as *"true if device has reset since the previous call of this routine"* (verified). A device that has just powered on has, trivially, reset since the previous call — there was no previous call. If the first call happens in `updateInputs`, then on **every** boot the first `periodic()` sees a "reset", runs a second blocking full-config `applyVerified` on the enabled hot path, raises the *"device reset detected… check the power and CAN wiring"* alert, and starts `deviceResetCount` at 1 — which trains a team to ignore the one alert that actually matters mid-match. So:

```java
// LAST STATEMENT of the TalonFXMotorIO constructor, after applyVerified and after the
// followers are configured. Consume-and-discard, once per device.
m_leader.hasResetOccurred();
for (TalonFX f : m_followers) f.hasResetOccurred();
```

`MotorInputs.deviceResetCount` therefore counts **post-boot** resets only, and that is what its javadoc and the `/Rootstock/<name>/DeviceResetCount` schema entry say. **[UNVERIFIED]** whether the Phoenix 6 26.x implementation in fact latches the power-on reset into the first call — CTRE's javadoc states the "since the previous call" contract but does not state the boot behaviour explicitly. The constructor call is correct **either way**: if the flag is not set at boot, consuming it is a no-op costing one CAN-free method call per device at construction. `DeviceResetRecoveryTest` (§11 item 8) gains a case asserting `deviceResetCount == 0` after a clean sim boot and one full `periodic()`.

```java
@Override public void updateInputs(MotorInputs in) {
  StatusCode ok = m_signals.refreshInto(in);   // subscribed channels only; rest stay NaN
  in.connected = ok.isOK();
  in.deviceResetCount = m_deviceResetCount;

  // A Phoenix 6 device that resets (brownout, CAN glitch, a single motor power-cycling) comes
  // back with its PERSISTED config but NO ACTIVE CONTROL REQUEST, and outputs neutral. An
  // elevator held by Motion Magic silently falls. Nothing in rev 1 re-sent anything, and
  // nothing checked. CTRE ships hasResetOccurred() for exactly this.
  // The BOOT flag was already consumed in the constructor, so this only ever fires post-boot.
  if (m_leader.hasResetOccurred()) {
    m_deviceResetCount++;
    in.deviceResetCount = m_deviceResetCount;
    m_leader.clearStickyFaults(0.0);                       // non-blocking
    PhoenixUtil.applyVerified(m_leader, m_config, m_name); // full config, blocking -- correct here
    m_lastGoalRot = Double.NaN;                            // force the next goal to be re-sent
    m_lastGoalRps = Double.NaN;
    m_lastArbFf   = Double.NaN;
    m_resetAlert.text(m_name + ": device reset detected on CAN "
        + m_leader.getDeviceID() + " bus '" + m_bus + "' (reset #" + m_deviceResetCount
        + "). Config and setpoint were re-sent. If this repeats, check the power and CAN"
        + " wiring to that motor -- a mid-match reset drops the mechanism until we notice.")
        .set(true);
    // m_resetAlert is created ONCE in the constructor:
    //   Alerts.warning(m_name, m_name + "/device-reset", MatchImpact.BLOCKS_MATCH)   [D10, D12]
    // Revision 3 called Rootstock.alerts().warning(group, text) here -- a deleted facade (D12)
    // with a two-argument signature D10 does not allow -- and it ALLOCATED a fresh alert plus
    // four string concatenations INSIDE periodic(), violating principle 9. Both fixed.
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

The reset path is the **one** place a blocking apply is allowed outside construction: it happens at most a handful of times per match, the alternative is a dropped mechanism, and it is logged as an epoch so a `RootstockTracer` spike is explained rather than mysterious.

#### 3.5.6 Writes — latch-preserving, with a heartbeat

> **BLOCKING CORRECTION, revision 4. Read this before the code.** Revision 3 wrote
> `m_mmVoltage.withPosition(outputRot).withVelocity(outputRps)` and an identical
> `MotionMagicExpoVoltage` twin. **Neither method exists.** Re-verified 2026-08-07 against the
> Phoenix 6 javadoc:
>
> | Request | Has `Velocity`? | Meaning | Has `FeedForward`? | Source |
> |---|---|---|---|---|
> | `MotionMagicVoltage` | **NO** | — | **yes**, *"Feedforward to apply in volts"* | [javadoc](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/controls/MotionMagicVoltage.html) |
> | `MotionMagicExpoVoltage` | **NO** | — | **yes** | [javadoc](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/controls/MotionMagicExpoVoltage.html) |
> | `DynamicMotionMagicVoltage` | yes — **but it is the CRUISE velocity**: *"Cruise velocity for profiling. The signage does not matter as the device will use the absolute value for profile generation."* There is **no** separate goal-velocity field. | rot/s | **yes** | [javadoc](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/controls/DynamicMotionMagicVoltage.html) |
> | `PositionVoltage` | **yes, genuinely** — *"Velocity to drive toward in rotations per second. This is typically used for motion profiles generated by the robot program."* | rot/s | **yes**, *"added to the output of the onboard feedforward terms"* | [javadoc](https://api.ctr-electronics.com/phoenix6/latest/java/com/ctre/phoenix6/controls/PositionVoltage.html) |
>
> So revision 3's `ON_MOTOR_PROFILED` branch **did not compile**, and its `DynamicMotionMagicVoltage`
> branch chained `.withVelocity(outputRps).withVelocity(cruise)` — the second call silently
> overwriting the first, so the field-locked turret's counter-rotation term was **dropped by the
> library**, which is the exact `TurretIONeo` defect this section is written to fix. Both are
> corrected below.
>
> **The fix, and why it is the right one rather than the convenient one.** Motion Magic's own
> profile generates a velocity reference internally and applies `Slot0.kV` to it; when the profile
> converges, that reference goes to zero. A field-locked turret needs the *opposite*: a
> steady-state velocity held at the goal. Those two requirements do not conflict, they compose —
> so the goal-velocity term becomes an **explicit voltage** riding on the request's
> arbitrary-feedforward field:
>
> ```
>   ffVolts = arbFfVolts + kV_device * goalRps
>
>   units:  [V] = [V] + [V / (output rot/s)] * [output rot/s]     ✔
>   kV_device is EXACTLY Slot0.kV as computed in §3.5.4 step 6:
>       kV_device = Gains.kV [V/(SI/s)] * MechanismUnits.siPerOutputRotation() [SI/rot]
>   -- the same number, from the same place, so this introduces no new conversion and no new
>      place for a unit error. It is recomputed whenever applyGains() changes kV.
> ```
>
> **kS is deliberately NOT added.** `Slot0.StaticFeedforwardSign = UseClosedLoopSign` (§3.5.4 step
> 6) already makes the device apply kS against the closed-loop direction; adding a second kS term
> here would double it and make a holding turret chatter — the precise failure that setting was
> chosen to prevent.
>
> **What is lost, stated rather than hidden.** On `NATIVE_REQUEST_FIELD` (`PositionVoltage`) the
> velocity term enters the device's *error* computation. On `DEVICE_FEEDFORWARD_VOLTS` (all three
> Motion Magic requests) it enters only the output sum, so residual velocity error is corrected by
> the position loop one step later rather than by a velocity loop immediately. For chassis-omega
> counter-rotation, where ω changes on the timescale of a driver's wrist and the position goal is
> updated every loop anyway, the difference is not observable. For a genuinely
> velocity-profiled position move it would be, which is why `RIO_PROFILE_MOTOR_LOOP` routes to
> `PositionVoltage` and gets the native field. `describe()` prints which carrier is in use, and
> `FieldLockedTurretParityTest` (§11 item 11) asserts the commanded output matches across
> backends to within the kV tolerance.

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
    m_routingFaultAlert.set(true);      // Alerts.error(m_name, m_name + "/internal-routing-fault",
                                        //   MatchImpact.BLOCKS_MATCH), built ONCE in the constructor
                                        //   with the full message text below. [D10, D12]
    //   "<name>: internal routing error -- ControlLocation.RIO_FULL reached the on-motor goal
    //    path. This is a Rootstock bug, not your config. The mechanism is now holding position
    //    and this call is a no-op. Please file it with `rootstock doctor --bundle`."
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

  // The Motion Magic requests have NO velocity field (verified above), so the goal-velocity
  // term rides as VOLTS on the arbitrary-feedforward field. m_kVDevice is Slot0.kV -- one
  // double, recomputed in the constructor and again in applyGains(), never here.
  //   m_kVDevice = gains.kV() * m_units.siPerOutputRotation();      // V per (output rot/s)
  double mmFfVolts = arbFfVolts + m_kVDevice * outputRps;

  if (override != null && m_dynamicCapable) {
    // DynamicMotionMagicVoltage.Velocity IS THE CRUISE VELOCITY -- "Cruise velocity for
    // profiling" (verified). There is exactly ONE withVelocity call on this chain and it is
    // the constraint override, never the goal velocity. Revision 3 chained two and the second
    // silently ate the first.
    m_leader.setControl(m_mmDynamic
        .withPosition(outputRot)
        .withVelocity(m_units.toOutputRps(override.maxVelocity()))        // CRUISE, not goal
        .withAcceleration(m_units.toOutputRps2(override.maxAcceleration()))
        .withJerk(m_units.toOutputRps3(override.jerk()))                  // rot/s^3, converted
        .withFeedForward(mmFfVolts)                                       // arbFf + kV * goalRps
        .withEnableFOC(m_foc));
    return;
  }

  switch (m_location) {
    // ---- DEVICE_FEEDFORWARD_VOLTS: no Velocity field on either Motion Magic request -------
    case ON_MOTOR_PROFILED -> m_leader.setControl(
        m_useExpo ? m_mmExpo.withPosition(outputRot)
                            .withFeedForward(mmFfVolts).withEnableFOC(m_foc)
                  : m_mmVoltage.withPosition(outputRot)
                               .withFeedForward(mmFfVolts).withEnableFOC(m_foc));
    // ---- NATIVE_REQUEST_FIELD: PositionVoltage really does have Velocity ------------------
    //      arbFfVolts is passed RAW here -- adding kV * rps would double-count, because the
    //      device applies Slot0.kV to the Velocity reference itself.
    case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP -> m_leader.setControl(
        m_posVoltage.withPosition(outputRot).withVelocity(outputRps)
                    .withFeedForward(arbFfVolts).withEnableFOC(m_foc));
    case RIO_FULL -> { /* unreachable; handled above */ }
  }
}

/** What this backend reports, so describe() and the parity test can name the path. */
@Override public MotorCapabilities capabilities() {
  return new MotorCapabilities(/* ... */,
      /* positionGoalVelocity */
      switch (m_location) {
        case ON_MOTOR_PROFILED                    -> VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS;
        case ON_MOTOR_DIRECT, RIO_PROFILE_MOTOR_LOOP -> VelocityCarrier.NATIVE_REQUEST_FIELD;
        case RIO_FULL                             -> VelocityCarrier.RIO_VOLTAGE_TRIM;
      },
      /* ... */);
}
```

**A `ConfigError` falls straight out of this, and it is worth having.** A mechanism that declares `RotaryAxis.turret(...).fieldLocked(gyro)` **and** `useExpo(true)` gets Motion Magic Expo, whose profile is shaped entirely by `MotionMagicExpo_kV`/`_kA` and which therefore fights a constant velocity offset harder than the trapezoid does. That is legal, it works, and it is worse — so it is a **Tier-2 alert**, not a fatal:

```
[Rootstock][WARN] Turret: RotaryAxis.fieldLocked(gyro) with control.useExpo() = true.
  The chassis-omega counter-rotation term reaches a Motion Magic request as arbitrary
  feedforward volts (kV x omega), because MotionMagicExpoVoltage has no velocity field.
  Expo re-shapes its profile from kV/kA continuously, so a sustained velocity offset is
  tracked less crisply than under the trapezoid.
  Suggested: useExpo(false) for field-locked axes, or ControlLocation.ON_MOTOR_DIRECT --
  PositionVoltage carries the velocity NATIVELY (VelocityCarrier.NATIVE_REQUEST_FIELD)
  and is the better fit for an axis you are already profiling from the RIO.
  describe() prints the carrier in use permanently.
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
> `org.rootstock.mechanism..` or `org.rootstock.hardware..` outside constructors, static
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
  goal-velocity path  DEVICE_FEEDFORWARD_VOLTS -- MotionMagicVoltage has no Velocity field, so
                      a nonzero goal velocity rides as kV_device x rps volts on withFeedForward
                      (kV_device = 5.00 x 0.27940 = 1.3970 V/(rot/s)).  Not field-locked, so
                      the term is 0.000 V every loop; it is printed anyway so it is never a
                      surprise.  (ON_MOTOR_DIRECT would give NATIVE_REQUEST_FIELD.)
  signals subscribed  POSITION@100Hz VELOCITY@100Hz APPLIED_VOLTS@50Hz STATOR@50Hz
                      SUPPLY@20Hz TEMPERATURE@4Hz
                      324 frames/s upper bound = 6.5 frames per 20 ms
                      x 140 bits/frame = 45.4 kbit/s = 4.5% of a 1 Mbps "rio" bus
                      (frame cost derived in §3.5.1; Phoenix status-frame packing is
                       [UNVERIFIED], so this is an UPPER BOUND, not an estimate)
  bus budget "rio"    Elevator 2 motors  9.1% | Arm 1 motor  4.5% | Roller (Spark)  n/a
                      declared drivetrain allowance: NONE DECLARED (assumed 0%)
                      total accounted: 13.6% of 1 Mbps.  Threshold for a Tier-2 alert: 60%.
  signals NOT read    CLOSED_LOOP_REFERENCE, TORQUE_CURRENT, FORWARD_LIMIT, REVERSE_LIMIT
                      -> those MotorInputs fields are NaN, not 0.0
  gains -> device     kP 80.0 V/m   -> Slot0.kP 22.35 V/rot     (x 0.27940 m/rot)
                      kD  2.0 V/(m/s) -> Slot0.kD  0.5588
                      kS  0.22 V     -> Slot0.kS  0.22          (volts, unconverted)
                      kV  5.00 V/(m/s) -> Slot0.kV  1.3970
                      kG  0.33 V     -> Slot0.kG  0.33, Elevator_Static
                      StaticFeedforwardSign = UseClosedLoopSign
```

*(Revision 4: `kG` was printed as `0.15 V` here and in §5.4's derivation table. It is **0.33 V** — the derivation dropped the cascade ×2. `travel per drum rot` was `0.280293 m` from the pitch-circle circumference; chain advance is exactly `teeth × pitch = 0.279400 m`, which moves `Slot0.kP` from 22.42 to 22.35 and `Slot0.kD` from 0.561 to 0.5588. See §5.4 and §4.3 for both derivations in full.)*


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
| *(units — the part revision 3 did not check)* | `cruiseVelocity`: *"Natively, the units are in RPM but will be affected by the velocity conversion factor."* `maxAcceleration`: *"Natively, the units are in RPM per second but will be affected by the velocity conversion factor."* `allowedProfileError`: *"Natively, the units are in rotations but will be affected by the position conversion factor."* | **Verified verbatim**, [MAXMotionConfig javadoc](https://codedocs.revrobotics.com/java/com/revrobotics/spark/config/maxmotionconfig). This is the sentence that makes the revision-3 `* 60.0` a **60× error** — see the derivation below `buildConfig`. |
| `closedLoop.velocityFF(...)` | `closedLoop.feedForward.kV(...)` | `FeedForwardConfig` is new: `kS, kV, kA, kG, kCos, kCosRatio`. **Verified.** |

The `kG` / `kCos` addition is the most important 2026 REV change for this library: **REVLib now does gravity feedforward on the controller**, `kG` static for elevators and `kCos` multiplied by the cosine of absolute mechanism position for arms — the exact split Phoenix expresses as `Elevator_Static` / `Arm_Cosine`. That means `GravityMode` maps cleanly to *both* vendors with no RIO-side term, which is the reason `Gains` can be a single shared record (§4.3).

```java
package org.rootstock.hardware.rev;

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

    // MAXMotion constraints are expressed in the CONVERTED units established above.
    // NO *60.0 -- see the derivation immediately after this class. Revision 3 multiplied by 60
    // "to convert to RPM" on top of a conversion factor that had already left RPM behind, and
    // commanded a cruise velocity 60x too fast on every REV elevator and arm.
    m_config.closedLoop.maxMotion
        .cruiseVelocity(u.toOutputRps(c.control().constraints().maxVelocity()))   // output rot/s
        .maxAcceleration(u.toOutputRps2(c.control().constraints().maxAcceleration())) // rot/s^2
        .allowedProfileError(u.toOutputRotations(c.control().tolerance()));       // output rot

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
    // The arbitrary-feedforward overload EXISTS -- verified, and recorded as closed in
    // DESIGN.md §5.6:
    //   setSetpoint(double setpoint, ControlType, ClosedLoopSlot, double arbFeedforward,
    //               ArbFFUnits)
    // There is still no REV analogue of PositionVoltage.withVelocity(), so SparkMotorIO reports
    //   capabilities().arbitraryFeedforward()   == true
    //   capabilities().positionGoalVelocity()   == VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS
    // and carries the goal-velocity term the same way the Phoenix Motion Magic path does:
    // kV_device x outputRps volts, added to arbFfVolts, in ArbFFUnits.kVoltage. Identical
    // formula, identical units, one shared helper -- which is what makes
    // FieldLockedTurretParityTest a meaningful test rather than a tautology.
    // It is NEVER silently dropped, which is precisely what TurretIONeo in
    // 0000-XXXX-Robot-Template:62-64 does.
    m_loop.setSetpoint(outputRot, t, ClosedLoopSlot.kSlot0,
                       arbFfVolts + m_kVDevice * outputRps, ArbFFUnits.kVoltage);
  }

  /** REV has no dynamic MAXMotion. Constraint profiles force RIO_PROFILE_MOTOR_LOOP. */
  @Override public void setPositionGoal(double rot, double rps, double ff, MotionConstraints o) {
    setPositionGoal(rot, rps, ff);   // o is unreachable: capabilities().dynamicProfile()==false
  }
}
```

**MAXMotion units — the derivation, because a 60× profile error shipped here in revision 3.**

REVLib applies conversion factors to the encoder *and* to the MAXMotion constraints, in the same direction, with the same factor. Written out:

```
  native velocity unit                = rotor RPM              [rotor rot / min]
  velocityConversionFactor  f_v       = 1 / (G * 60)           [set on line 3 of buildConfig]

  converted velocity  = rotorRPM * f_v
                      = (rotor rot / min) / (G * 60)
                      = (rotor rot / s)   / G
                      = OUTPUT rot / s                                      ✔ what we want

  cruiseVelocity  is "natively RPM but ... affected by the velocity conversion factor"
      => the value REVLib expects is in the SAME converted unit: OUTPUT rot/s
      => pass  u.toOutputRps(maxVelocity)                                   ✔
      => passing  u.toOutputRps(maxVelocity) * 60.0  asks for 60x the cruise velocity.
         On the 9143 elevator that is an effectively unconstrained profile: the mechanism
         accelerates until MotionMagic's acceleration limit or the current limit stops it,
         which for a gravity-loaded carriage means it slams the top hard stop.

  maxAcceleration is "natively RPM per second but ... affected by the velocity conversion
      factor" -- SAME factor, applied once
      => converted unit = (OUTPUT rot/s) / s = OUTPUT rot/s^2
      => pass  u.toOutputRps2(maxAcceleration)                              ✔

  positionConversionFactor  f_p       = 1 / G
  allowedProfileError is "natively rotations but ... affected by the position conversion factor"
      => converted unit = OUTPUT rotations
      => pass  u.toOutputRotations(tolerance)                               ✔
```

This is the same unit-error class the library's core pitch is eliminating — the 41 inlined `/ 360.0` sites and the 57.3× `RIO_FULL` error — and revision 3 shipped it in the flagship REV backend, unmarked. The correction is verified against REV's own javadoc (quoted in the rename table above), but **the design does not get to assert it and move on.** Two gates:

* **`MaxMotionUnitsTest` (release-blocking, and it gates the REV adapter's ship).** Build a `SparkMotorIO` in simulation with `positionConversionFactor`/`velocityConversionFactor` set from a known reduction, command a `kMAXMotionPositionControl` move, iterate `SparkSim.iterate(...)`, and assert the observed steady-state profile velocity equals the configured `cruiseVelocity` within 5 %. This mirrors exactly the `SparkSim.iterate` pinning test `design/04` §7.2 mandates and that `DESIGN.md` §5.6 lists as still-open item 3, and it fails loudly if REV's semantics are ever not what the javadoc says.
* Until that test passes on real REVLib, this whole block is tagged **[UNVERIFIED-BY-EXECUTION]**: the *documentation* is verified, the *behaviour* is not, and that distinction is exactly what revision 3 collapsed.

**Connection health.** Every `*IONeo` in `0000-XXXX-Robot-Template` hardcodes `inputs.connected = true` with the comment "Spark MAX has no cheap connection signal". Rootstock does better: `in.connected = !m_leader.hasActiveFault() && m_leader.getFirmwareVersion() != 0` plus a heartbeat check that the warning counter is not stuck. The REVLib 2026 fault-query names are **verified** and recorded as closed in `DESIGN.md` §5.6: `hasActiveFault()`, `hasStickyFault()`, `hasActiveWarning()`, `hasStickyWarning()`, plus `getFaults()`/`getWarnings()` returning `Faults`/`Warnings` objects. (Revision 3 marked these `[UNVERIFIED]`; the integration pass closed them and this document had not caught up.) This is a real behavior gap, not cosmetic — a disconnected Spark currently reports healthy on this robot. What remains **[UNVERIFIED]** is whether REVLib exposes anything equivalent to Phoenix's `hasResetOccurred()`; until it does, the §3.5.5 reset-recovery guarantee is Phoenix-only and `describe()` must say so on every REV mechanism (open question 3).

**Simulation parity.** The NEO path in `0000-XXXX-Robot-Template` has *no simulation at all*, so "switch one constant to NEO" ships untested code. `SparkMotorIO` implements sim via `SparkMaxSim`/`SparkFlexSim` with `SparkSim.iterate(velocity, vbus, dt)` driven by the same physics plant the Phoenix backend uses. Vendor parity in sim is a release requirement, not a nice-to-have.

### 3.8 Backends: generic and sim

**`GenericMotorIO`** wraps any WPILib `MotorController` plus any `Encoder`/`DutyCycleEncoder`, forces `ControlLocation.RIO_FULL`, and runs a `TrapezoidProfile` + `PIDController` + `Controllers.Feedforward` **entirely in SI** — the identical code path `PositionMechanism` uses for `RIO_FULL` on any backend (§6.2), not a second implementation.

Revision 1 said `GenericMotorIO` runs *"the matching WPILib feedforward (`ElevatorFeedforward` for `CONSTANT`, `ArmFeedforward` for `COSINE`, `SimpleMotorFeedforward` for `NONE`)"* and called it through a `m_feedforward` field. **That does not compile**: those three classes have no common supertype in WPILib 2026, and their `calculate` signatures differ in arity. `Controllers.feedforward(gravityMode, siGains, Clock.dt())` (§1.5) is the sealed dispatcher that makes the sentence true, and it is the *only* place the three WPILib classes are named.

`GenericMotorIO` reports `false` for every on-board capability so the downgrade message in §3.2 fires exactly once at boot.
*2027 note:* `MotorController.set()` becomes `setThrottle()`; `DutyCycleEncoder`'s survival past the 2027 Counter removal is **[UNVERIFIED]**.

**`SimMotorIO`** is a *full* `MotorIO` backed by a WPILib plant plus a RIO-side loop. It is used when `MotorSpec.sim()` is selected, and — critically — it is **not** how the Phoenix/REV backends simulate. Those simulate through their own vendor sim state (`TalonFXSimState`, `SparkMaxSim`) so that simulation exercises the *real* config path, including `SensorToMechanismRatio` and Motion Magic. `SimMotorIO` is for teams with no vendor libs installed at all.

### 3.9 Configuring hardware: two paths, and only two

Team 4738's `DeviceUtil.applyParameter` (retry + read-back verification, short-circuit in sim) is the best config primitive found in the survey, and `0000-XXXX-Robot-Template`'s `PhoenixUtil.tryUntilOk` is the weaker version that was *forgotten* in `ModuleIOTalonFX`, `ModuleIONeo`, and `GyroIOPigeon2`.

Revision 1 made `applyVerified` *"the only way CORE configures hardware"* and then had `MotorIO.applyGains` — documented "safe to call every loop" and invoked from `periodic()` whenever `m_tunables.anyChanged()`, at `design/02` §5.4's specified 10 Hz write-through — go through it. `TalonFXConfigurator.apply(config)` blocks with a default **0.050 s** timeout and `refresh()` blocks too, so up to 5 verified rounds is **500 ms of blocked main loop**, ten times a second, on the exact workflow the library is built around: a student dragging a slider in AdvantageScope. That is a guaranteed loop overrun, shipped in the library.

**The config path splits in two, permanently.**

```java
package org.rootstock.hardware.phoenix;

public final class PhoenixUtil {
  private static final int ATTEMPTS = 5;

  // ------------------------------------------------------------------ CONSTRUCTION PATH
  /**
   * Apply, then read back and compare, up to ATTEMPTS times. BLOCKING (default 0.050 s per
   * apply, plus a refresh). Raises a persistent Alert on failure; never throws.
   *
   * LEGAL CALLERS, exhaustively:
   *   1. a MotorIO constructor,
   *   2. MotorIO.reapplyFullConfigBlocking(),
   *   3. RootstockRegistry.onDisable(),
   *   4. SelfTest,
   *   5. MotorIO.applyRaw(),
   *   6. the hasResetOccurred() recovery path in §3.5.5,
   *   7. HOMING START and HOMING COMPLETION (added in revision 4 -- see §6.3 and the
   *      justification immediately below this class).
   * It is ILLEGAL from an enabled periodic() that is not one of the above.
   *
   * @return true if the configuration was applied AND read back matching. Callers that must
   *         not proceed on failure (homing) check this; callers that only need best effort
   *         (construction, disabledInit) ignore it and rely on the raised alert.
   */
  public static boolean applyVerified(ParentDevice device, Object config, String owner) {
    if (Platform.isSimulation()) { apply(device, config); return true; }
    for (int i = 1; i <= ATTEMPTS; i++) {
      StatusCode applied   = apply(device, config);
      StatusCode refreshed = refresh(device, readBack);
      if (applied.isOK() && refreshed.isOK() && matches(config, readBack)) {
        if (i > 1) Alerts.warning(owner, owner + "/config-retried",
            MatchImpact.PIT_ONLY).text(
            owner + ": config applied on attempt " + i + " (CAN bus was busy at boot).")
            .set(true);
        return true;
      }
    }
    Alerts.error(owner, owner + "/config-apply-failed", MatchImpact.BLOCKS_MATCH).text(
        owner + ": config did NOT apply after " + ATTEMPTS
        + " attempts (device " + device.getDeviceID() + " on bus '" + busName(device) + "'). "
        + "The mechanism will run with WHATEVER was previously on the device. "
        + "Check CAN wiring and device ID.").set(true);
    return false;
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

**Why homing was added to the legal-caller list in revision 4.** §6.3 suspends the device soft limits during homing (you are deliberately driving into a stop) and temporarily reduces the stator limit — two device config writes made **while enabled**. Revision 3's legal-caller list did not include homing, which left exactly one path available: `applyFast`, fire-and-forget with no read-back. **A lost restore frame after homing completes leaves the device soft limits DISABLED for the rest of the enable period** — until the next `disabledInit()` verified re-apply — on a current-spike-homed elevator with no absolute encoder, i.e. the mechanism class least able to tolerate it. A section headed *"this is a mechanism-damage surface, so the guarantees are hard"* cannot leave its most consequential config transaction unspecified.

Homing gets the same exemption the reset-recovery path already has (§3.5.5), for the same reasons and with the same bound:

* It happens **at most once per enable**, and in practice once per boot — not on a periodic cadence.
* The alternative is unverified, and an unverified restore is a silently disarmed safety system.
* The blocking cost lands where the robot is *already* moving slowly under a 3.0 V clamp into a hard stop, so a 50–250 ms stall in the loop degrades nothing that is not already degraded. It is logged as a `RootstockTracer` epoch (`Mechanism/HomingConfig`, budget 300 ms) so the spike is explained rather than mysterious.
* **The restore is checked.** `applyVerified` now returns a boolean; homing's completion step *must* see `true`. If it does not, the mechanism goes **neutral immediately**, `isHomed()` stays **false**, and `<name>/homing-limits-unrestored` (`MatchImpact.BLOCKS_MATCH`) latches with the exact text below. A mechanism whose soft limits could not be confirmed does not get to keep moving.

```
[Rootstock][ERROR] Elevator: homing finished but the SOFT LIMIT RESTORE could not be verified
  after 5 attempts (TalonFX 20 on bus "rio").
  Device soft limits may still be DISABLED. The mechanism is neutral and isHomed() is false, so
  goTo() and every Superstructure transition that requires this axis will refuse.
  Java-side clamping (§6.2) is still active, so a commanded goal cannot exceed the limits -- but
  the firmware backstop that works when robot code hangs is not confirmed.
  Fix: disable and re-enable (disabledInit() runs the verified full-config re-apply), then check
  CAN wiring to that motor.
```

The temporary stator-limit reduction takes **the same treatment**: applied through `applyVerified` at homing start, restored through `applyVerified` at completion, and an unconfirmed restore latches `<name>/homing-limits-unrestored` with the current-limit clause added. A mechanism left running at `threshold × 1.5` amps for the rest of a match is a slow elevator nobody can explain; a mechanism left with no soft limits is a broken one.

Enforced, not just documented:

```java
// ArchUnit, release-blocking.
noClasses().that().resideInAnyPackage("org.rootstock.mechanism..", "org.rootstock.superstructure..")
           .should().callMethod(PhoenixUtil.class, "applyVerified", ParentDevice.class,
                                Object.class, String.class);

// And a budget, so a regression is visible instead of inferred from loop overruns:
RootstockTracer.budget("Mechanism/ApplyGains",    Milliseconds.of(1.0));
RootstockTracer.budget("Mechanism/Periodic",      Milliseconds.of(2.0));
RootstockTracer.budget("Mechanism/HomingConfig",  Milliseconds.of(300.0));  // rev 4, §6.3
```

The ArchUnit rule above bans `applyVerified` from `org.rootstock.mechanism..` wholesale, and homing lives there — so the rule is narrowed to match the corrected legal-caller list: **`HomingStrategy` implementations and `PositionMechanism`'s homing sub-state machine are the named exception**, and the rule is written as a class-name allowlist rather than a package ban so that adding a second exception requires editing the rule.

### 3.10 Encoders

**What is abstracted:** the *question* "what is the mechanism's absolute position, and do I trust it?"
**What is passed through:** everything else.

```java
package org.rootstock.hardware;

public interface AbsoluteEncoderIO {
  void updateInputs(AbsoluteEncoderInputs inputs);
  <T extends AbsoluteEncoderIO> Optional<T> as(Class<T> type);

  class AbsoluteEncoderInputs implements LoggableInputs {
    public boolean connected = false;
    public double  absolutePositionRot = Double.NaN;  // OUTPUT rotations, offset already applied
    public double  velocityRps         = Double.NaN;
    public double  rawPositionRot      = Double.NaN;  // before offset: what you read at the hard stop
    @Override public void toLog(LogTable t)   { /* one put per field, verbatim names */ }
    @Override public void fromLog(LogTable t) { /* one get per field, same names */ }
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

**The guarded re-seed.** `9143-2025-A-Updated/src/main/java/frc/robot/subsystems/CorAl.java:286-344` contains a genuinely hard-won rule that Rootstock owns so nobody rediscovers it:

> Seed the motor's internal sensor from the absolute encoder at boot, and again immediately before starting a move — but **never** during a move, because shifting the reference frame mid-motion makes the mechanism land off target. Re-sync from `periodic()` only when neither closed-loop nor manual control is active. Use `setPosition(value, 0.0)`; the default overload blocks the main loop up to 100 ms.

`PositionMechanism` implements exactly that, for `RemoteCancoder`, `SparkAbsolute`, and `DioAbsolute`. For `FusedCancoder` it does **nothing**, because Phoenix already fuses on-device — and `describe()` says so, so a student can see why the code path differs.

**Disagreement alert.** When both an absolute source and the rotor are available, CORE continuously compares them and raises:

> **PEDAGOGICAL EXAMPLE — the configured value below is DELIBERATELY WRONG. `[INTENTIONAL-34.126]`**
> The whole premise of this alert is that `rotorPerSensor` has been *mis-entered*, so the number
> the alert echoes back must be the wrong one (`34.126`) and not the arm's true ratio (`65.411`).
> It is the same wrong/right pair as the `ConfigError` example immediately below, on purpose:
> a reader who sees `34.126` in the alert and `34.126 → 65.411` in the `ConfigError` is looking
> at one defect told twice, which is exactly the teaching point.
> **Do not "correct" this to 65.411.** Revision 5 found that a `34.126 → 65.411` literal sweep
> had done exactly that, leaving the alert reading *"wrong rotorPerSensor (currently 65.411)"* —
> self-contradictory, because 65.411 is the value the very next block prescribes as the fix.

```
[Rootstock][ERROR] Arm: absolute encoder and motor sensor disagree by 14.2 deg
  (absolute 41.3 deg, motor 27.1 deg, tolerance 2.0 deg).
  Likely causes: wrong FusedCancoder.rotorPerSensor (currently 34.126), a slipped belt,   [INTENTIONAL-34.126]
  or a magnet offset that was captured at a different mechanical position.
  (The true ratio for this arm is 65.411:1 -- see the ConfigError below.)
```

**And the class of error this alert used to *describe* is now caught before it can happen.** Per **D2b**, `Validation` carries a Tier-1 rule: for `FeedbackSpec.FusedCancoder` and `FeedbackSpec.RemoteCancoder`, `rotorPerSensor × sensorPerOutput` **must** equal `reduction.rotorPerOutput()` within 1 %, or the config collects a `FATAL` `ConfigError` naming both numbers, both fields and the identity. Phoenix requires `RotorToSensorRatio × SensorToMechanismRatio == rotor-per-output`; revision 1 mis-scaled the flagship fused arm by **1.92×** in the very example used to demonstrate "change the gear ratio in exactly one place", and nothing caught it. A corrected literal is one review away from drifting again; the rule is the actual fix.

> **PEDAGOGICAL EXAMPLE — the `field` values below are DELIBERATELY WRONG. `[INTENTIONAL-34.126]`**
> `34.126` is the *mis-entered* value the rule catches; `65.411` is the truth it is compared
> against. Both numbers are load-bearing and neither may be swept.

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Arm" (PositionConfig)
  (example output; the field values are the intentionally wrong ones)   [INTENTIONAL-34.126]

  field    feedback.rotorPerSensor  = 34.126
  field    feedback.sensorPerOutput = 1.000
  product                           = 34.126
  field    reduction.rotorPerOutput = 65.411   ((58:10) x (58:18) x (42:12))

  Phoenix requires RotorToSensorRatio x SensorToMechanismRatio == rotor rotations per output
  rotation. These disagree by 1.92x, so the arm would read and command 1.92x every angle --
  a 45 deg goal would land at 23.5 deg, and cosine gravity would be applied at the wrong angle
  over the whole range.

  Fix: rotorPerSensor = 65.411 (the CANcoder is on the joint, so sensorPerOutput = 1.0).
  If your CANcoder is NOT on the joint, set sensorPerOutput to the gearing between it and the
  output shaft and rotorPerSensor to the gearing between the rotor and it -- the product still
  has to be 65.411.
```

### 3.11 Gyros

CORE owns only the minimal seam; Drivetrain (`design/05`) owns odometry, high-frequency sampling, and the yaw thread.

```java
public interface GyroIO {
  void updateInputs(GyroInputs inputs);
  void setYaw(Rotation2d yaw);
  <T extends GyroIO> Optional<T> as(Class<T> type);

  class GyroInputs implements LoggableInputs {
    public boolean connected = false;
    public Rotation2d yaw = Rotation2d.kZero;
    public double yawVelocityRadPerSec = 0.0;
    public Rotation2d pitch = Rotation2d.kZero, roll = Rotation2d.kZero;
    public double accelXG = 0.0, accelYG = 0.0, accelZG = 0.0;
    /** Filled ONLY by Drivetrain's high-frequency thread; empty for CORE consumers. */
    public double[] odometryTimestamps = new double[0];
    public Rotation2d[] odometryYawPositions = new Rotation2d[0];
    @Override public void toLog(LogTable t)   { /* ... */ }
    @Override public void fromLog(LogTable t) { /* ... */ }
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

  class DigitalSensorInputs implements LoggableInputs {
    public boolean connected = false;
    /** THE level signal. Debounced by the library. Never an edge -- 9143-A needed a 5-line
     *  workaround because CorAl's auto-stop fired only on the rising edge. */
    public boolean detected = false;
    /** Meters. NaN when the sensor cannot measure distance (switch/beam break). */
    public double distanceMeters = Double.NaN;
    @Override public void toLog(LogTable t)   { /* ... */ }
    @Override public void fromLog(LogTable t) { /* ... */ }
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

**Hard limits vs soft limits.** A hard limit wired *into the motor controller* is configured through `HardwareLimitSwitch` and stops the motor in firmware — always preferred, and Rootstock enables it whenever `SensorSpec.motorLimit` is used. A hard limit on the RIO's DIO cannot stop the motor in firmware; Rootstock zeroes the output in the same loop and raises a warning at config time explaining the latency difference. Soft limits are configured on the device (§3.5.4 step 5) **and** re-clamped in Java before every goal, so a soft limit is enforced even when the device config failed to apply.

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
| `Gains.kP` | `double` | **V/m** (linear) or **V/rad** (rotary) | `org.rootstock.control.Gains`, D1 |
| `Gains.kV` | `double` | **V/(m/s)** or **V/(rad/s)** | same |
| `Gains.kS`, `Gains.kG` | `double` | **V** | same |
| `MotionConstraints.maxVelocity` | `double` | **user**/s (m/s or **deg**/s) | `MotionConstraints` |
| `MotionConstraints.maxAcceleration` | `double` | **user**/s² | same |
| `MotionConstraints.jerk` | `double` | **user**/s³ | same — and the row revision 3's `buildConfig` ignored (§3.5.4 step 7). Every one of these three converts to output rot/sⁿ at the seam via `toOutputRps` / `toOutputRps2` / **`toOutputRps3`**; none of them is ever passed raw. |
| `Setpoint.value`, `PositionLimits.min/max`, `tolerance` | `Measure<?>` | typed | config boundary |
| `PositionMechanism.goal()/measured()` | `double` | **user** (m or deg) | public API |
| `MotorIO.setPositionGoal(...)` | `double` | **output rotations**, **output rot/s**, **V** | the seam |

Gains are SI and constraints are user units **on purpose**: gains are machine numbers that the tuning wizard measures and `gains.json` persists, and they must be portable across geometry changes; constraints are numbers a driver reasons about out loud ("make the elevator go 1.6 m/s"). The split is stated once, printed by `describe()`, and asserted by `UnitsContractTest`.

### 4.2 `Reduction`

```java
package org.rootstock.pure.units;        // D7 / DESIGN.md §7. HAL-free, zero edu.wpi.first imports.

/**
 * A gearbox. Always POSITIVE. Direction is expressed by MotorGroup.leaderInverted(),
 * never by a negative ratio -- which makes `TURRET_ROTATOR_GEAR_RATIO = -20 / 200.0`
 * (8793-2026-Robot/.../Constants.java:45) unrepresentable, along with the
 * "gear ratio is negative, so signs cancel" reasoning it forced.
 */
public final class Reduction {
  private final double m_rotorPerOutput;    // rotor rotations per 1 output rotation
  private final String m_derivation;        // "(58:10) x (58:18) x (42:12) = 65.411:1"

  /** A 9:1 gearbox. */
  public static Reduction of(double rotorPerOutput);

  /** Reduction.ofStages(3.0, 4.0) -> 12.0:1. Matches the 9143 habit of writing
   *  ELEVATOR_GEAR_RATIO = 3.0 * 4.0 -- but machine-checkable and self-describing. */
  public static Reduction ofStages(double... stages);

  /**
   * One gear stage, BY TOOTH COUNT, in the order a student reads them off the gearbox:
   * the big gear (on the slow / OUTPUT side) first, the small gear (on the fast / MOTOR
   * side) second.
   *
   *     rotorPerOutput contribution = drivenTeeth / drivingTeeth
   *
   * so ofTeeth(58, 10) is a 5.8:1 REDUCTION, which is what every call in this library and in
   * DESIGN.md §10A.1 means by it.
   *
   * PARAMETER NAMES ARE LOAD-BEARING AND REVISION 3 HAD THEM BACKWARDS. It declared
   * `ofTeeth(int driving, int driven)`; a faithful implementation of that signature computes
   * 10/58 and turns the flagship arm into a 65x SPEED-UP. For a library whose central promise
   * is making gear-ratio errors unrepresentable, a signature whose faithful implementation
   * inverts every ratio in its own documentation is the worst possible spec bug, and it is
   * pinned by a test rather than by a comment:
   *
   *     ReductionTeethOrderTest:
   *       assertEquals(5.8,    Reduction.ofTeeth(58, 10).rotorPerOutput(),          1e-9);
   *       assertEquals(65.411, Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12)
   *                                     .rotorPerOutput(),                          1e-3);
   *
   * The disambiguating overload exists for anyone who does not trust the order -- and the
   * fact that you might not trust it is the reason it exists:
   *
   *     Reduction.ofGears(Teeth.of(58), Teeth.of(10))     // output-side, motor-side
   *
   * Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12) reproduces 9143-2025-A
   * CORAL_PIVOT_GEAR_RATIO exactly, and prints its own derivation.
   *
   * @param drivenTeeth   teeth on the DRIVEN gear -- the one on the OUTPUT side of this stage
   * @param drivingTeeth  teeth on the DRIVING gear -- the one on the MOTOR side of this stage
   */
  public static Reduction ofTeeth(int drivenTeeth, int drivingTeeth);
  public Reduction then(int drivenTeeth, int drivingTeeth);
  public Reduction then(double stage);

  public double rotorPerOutput();
  public double outputPerRotor();
  public String describe();      // "(58:10) x (58:18) x (42:12) = 65.411:1 (rotor per output)"

  /** Cross-check against an external source of truth (PathPlanner settings.json, TunerConstants). */
  public boolean approxEquals(double other, double relativeTolerance);
}
```

**The derivation, once, since this number appears throughout the document:**

```
  (58:10)  =  58 / 10  =  5.800000
  (58:18)  =  58 / 18  =  3.222222
  (42:12)  =  42 / 12  =  3.500000

  5.800000 x 3.222222  = 18.688889
 18.688889 x 3.500000  = 65.411111        ->  65.411 : 1   (rotor rotations per output rotation)
```

Revision 3 annotated this train as **34.126:1** in **nine** places in this document (§3.10's alert example, the two `Reduction` javadoc strings above, §5.5's comment and `rotorPerSensor`, §5.5's kG/kV derivation paragraph, §5.6's kG expectation text, and §9.1's two lines). All nine *live* annotations now read **65.411**. `DESIGN.md` §16 item 1's checklist named only three of the nine locations, so it is **not** a sufficient checklist and should not be used as one; the durable form of the check is a grep. `[INTENTIONAL-34.126]`

**The gate, and the carve-out it needs.** `[INTENTIONAL-34.126]` A bare `grep '34\.126'` over this document does **not** return zero, and must not be made to: this document deliberately retains the wrong value in about ten places — §3.10's *disagreement-alert* example and its `ConfigError` example (where 34.126 is the whole pedagogical point), the revision-4 rescale table and derivation below, the §11 test-list entry that names the defect, and this paragraph. Revision 5 found the previous sweep had over-applied and silently broken the §3.10 alert example by "correcting" it. So the gate carries an explicit marker:

> **`grep -rn '34\.126' .` must return zero matches** outside (a) `DECISIONS.md`, (b) `DESIGN.md`'s historical tables, and (c) sites carved out by the literal marker **`[INTENTIONAL-34.126]`**. **Marker scope** is the smallest contiguous block containing the literal: the same line, or — where an inline marker would corrupt sample output or a table row — any line of the same **fenced code block**, the same **block-quote**, or the same **markdown table**, plus the block-quote immediately preceding a fence. Any `34.126` with no marker in scope is a stale literal and fails the build.
>
> A companion gate runs in the other direction, because a sweep that over-applies is as bad as one that under-applies: **every `[INTENTIONAL-34.126]` marker must have a `34.126` in its scope.** A marker left standing over a site that now reads `65.411` means the wrong value was swept out of a place that needed it.

This is one of the literal-sweep checks that becomes a CI job at M24 and is run by hand at every revision until then.

### 4.3 `Axis` — geometry, SI domain, and where gravity comes from

```java
package org.rootstock.units;

/** Converts OUTPUT ROTATIONS to the units a human wants, declares the SI domain, and
 *  declares the gravity model. */
public sealed interface Axis permits LinearAxis, RotaryAxis {
  double userPerOutputRotation();     // meters per rot, or DEGREES per rot
  double siPerOutputRotation();       // meters per rot, or RADIANS per rot   <-- gains use THIS
  SiDomain siDomain();                // LINEAR_METERS | ROTATIONAL_RADIANS       [D3]
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
/** D3 names these LINEAR_METERS and ROTATIONAL_RADIANS. Revision 3 of this document wrote
 *  LINEAR / ANGULAR, which is a different spelling of the same two constants and would have
 *  been a compile error against `design/02`'s TuningTarget. The D3 spelling wins; the constant
 *  names carry their unit, which is the point of having the type at all. */
public enum SiDomain {
  LINEAR_METERS      { public double toSi(double user) { return user; }        // meters in, meters out
                       public double fromSi(double si) { return si; }
                       public String label() { return "m"; } },
  ROTATIONAL_RADIANS { public double toSi(double user) { return Math.toRadians(user); }  // deg -> rad
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
 * metersPerOutputRotation = 2*pi*effectiveRadius * stages.
 */
public record LinearAxis(Distance drumRadius, int stages) implements Axis {
  public LinearAxis {
    // NOTE: this compact constructor no longer THROWS. It contributes to the config's
    // collected error list instead. See §5.6.
  }

  /**
   * Sprocket by chain pitch and tooth count -- the way the part is actually specified.
   *
   * CHAIN ADVANCE PER REVOLUTION IS EXACTLY teeth x pitch. One link engages one tooth, so N
   * teeth pull N links, so the chain (and the carriage it is attached to) advances N*p per
   * drum revolution. The stored drumRadius is therefore the KINEMATIC radius that reproduces
   * that advance:
   *
   *     drumRadius = teeth * chainPitch / (2*pi)
   *
   * Revision 3 used the geometric PITCH-CIRCLE circumference instead --
   * pitchDiameter = pitch / sin(pi/teeth), circumference = pi * pitchDiameter -- and printed
   * the result to six significant figures. For 22 teeth at 0.25 in those differ:
   *
   *     22 x 0.25                     = 5.500000 in   (chain advance -- correct)
   *     pi x 0.25 / sin(pi/22)        = 5.518930 in   (pitch circumference -- 0.344% high)
   *
   * Over 55 in of elevator travel that is 0.19 in of accumulated position error, baked into a
   * constant that DescribeSnapshotTest string-asserts verbatim. The difference is chordal
   * action: the chain rides a polygon, not a circle. Six-figure precision on the wrong model
   * is worse than three figures on the right one.
   *
   * (The geometric pitch radius is still printed by describe(), labelled as such, because it
   * is what you measure with calipers and a student comparing the two should see both.)
   *
   * LinearAxis.sprocket(Inches.of(0.25), 22, 2) == 9143-A's ELEVATOR geometry.
   */
  public static LinearAxis sprocket(Distance chainPitch, int teeth, int stages);

  /** Belt by pitch and pulley teeth. Same physics, and revision 3 already had it right:
   *  pitchDiameter = pitch * teeth / pi, so circumference = pi * d = teeth * pitch -- exactly
   *  the tooth-count advance. sprocket() now agrees with pulley() instead of contradicting it. */
  public static LinearAxis pulley(Distance beltPitch, int teeth, int stages);

  /** Bare drum wrapped with cable or rope: here 2*pi*r IS the right model, because a cable has
   *  no teeth and no chordal action. Kept separate so the distinction is visible in a config. */
  public static LinearAxis drum(Distance drumRadius, int stages);

  @Override public double userPerOutputRotation() {
    return 2 * Math.PI * drumRadius.in(Meters) * stages;
  }
  @Override public double siPerOutputRotation() { return userPerOutputRotation(); }  // m == m
  @Override public SiDomain siDomain()  { return SiDomain.LINEAR_METERS; }
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
  @Override public SiDomain siDomain()  { return SiDomain.ROTATIONAL_RADIANS; }
  @Override public String unitLabel()   { return "deg"; }
  @Override public String siLabel()     { return "rad"; }
  @Override public double horizontalReference() { return horizontalAt.in(Degrees); }

  /** REQUIRED, and revision 3 omitted it. The Axis interface declares isContinuous(); the
   *  record component `continuous` generates an accessor spelled continuous(), which does NOT
   *  satisfy it -- so this record, written out in full rather than elided, did not compile.
   *  Call sites depend on it: §3.5.4 step 8 reads c.axis().isContinuous() to set
   *  ClosedLoopGeneral.ContinuousWrap, and §3.7 reads it to set REV positionWrapping. */
  @Override public boolean isContinuous() { return continuous; }
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
package org.rootstock.units;

/**
 * Built ONCE from (Reduction, Axis). Handed to the MotorIO, the physics sim, the soft-limit
 * derivation, the telemetry namespace, and the tuning UI. There is no other converter
 * in Rootstock, and mechanism code never performs a unit conversion by hand.
 */
public final class MechanismUnits {
  public MechanismUnits(Reduction reduction, Axis axis);

  // ---- seam <-> user -------------------------------------------------
  public double toUser(double outputRotations);          // rot -> m or deg
  public double toOutputRotations(double userUnits);
  public double toUserPerSec(double outputRps);
  public double toOutputRps(double userPerSec);
  public double toOutputRps2(double userPerSecSquared);
  /** Third derivative. NEW in revision 4, and its absence was a live bug: §3.5.4 step 7 and
   *  the DynamicMotionMagicVoltage path both passed MotionConstraints.jerk() RAW into a field
   *  documented as rot/s^3, so a rotary axis was off by 360 and a linear one by
   *  1/(travel per output rotation).
   *      toOutputRps3(j) == j / userPerOutputRotation()
   *  -- the identical shape as toOutputRps and toOutputRps2, which is exactly why leaving it
   *  out and inlining nothing in its place was so easy to miss. UnitsContractTest pins
   *  1.0 deg/s^3 -> 1/360 rot/s^3 and 1.0 m/s^3 -> 1/0.27940 rot/s^3. */
  public double toOutputRps3(double userPerSecCubed);
  public double toUserPerSec3(double outputRps3);

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
                      chain advance  = 22 x 0.250      = 5.5000 in per drum rot
                      kinematic radius = 5.5000 / 2pi  = 0.87535 in = 0.0222339 m
                      (geometric pitch dia = 0.250 / sin(pi/22) = 1.75669 in, so the pitch
                       circumference is 5.51893 in -- 0.34% larger. Chordal action: the chain
                       rides a polygon. Rootstock uses the CHAIN ADVANCE.)
  rigging             2 stages (cascade) -> the carriage moves 2x the drum surface
  travel per drum rot 0.279400 m  (11.0000 in)  = 2 x 22 x 0.250 in
  effective radius    0.0444679 m  (kinematic radius x 2 stages) -- this is what ElevatorSim
                      and the kG derivation both use
  travel per rotor rot 0.0232833 m  (0.91667 in)
  positive direction  UP (leader CCW-positive, not inverted)
  soft limits         0.000 m .. 1.3970 m  ==  0.000 .. 5.0000 drum rot
  free speed estimate 2 x Kraken X60 @ 5800 rpm free (FOC) -> 96.667 rotor rot/s
                      -> 8.0556 drum rot/s -> 2.251 m/s at the carriage
  cruise requested    1.60 m/s  (71% of free speed)  OK
  accel requested     6.00 m/s^2
                      force needed  = 6.00 x 10.886 kg + 106.76 N weight = 172.1 N
                      force available at the 70 A stator limit
                                    = 0.0194 N.m/A x 70 A x 2 motors x 12 / 0.0444679 m
                                    = 733 N   (4.3x margin)
                      unlimited stall (FOC, 9.37 N.m/motor) would be 5057 N; the current
                      limit is what you actually have
  SI domain           LINEAR_METERS  -- gains are volts-per-meter; toSi() is the identity
  gravity             CONSTANT (kG applied always, Phoenix Elevator_Static)
  ControlLocation     ON_MOTOR_PROFILED (defaulted -- your leader is a TalonFX, which runs
                      Motion Magic on the device at 1 kHz).  To change it: .controlLocation(...)
```

*(Revision 4 recomputed every number in this block. `travel per drum rot` moved from 0.280293 m to **0.279400 m** — see `LinearAxis.sprocket` above — and `soft limits` consequently land on an exact 5.0000 drum rotations rather than 4.984. `free speed` moved 2.26 → **2.251 m/s**. The `accel` row previously quoted a bare "the pair stalls near 3800 N", which mixed the FOC free speed with the **non**-FOC stall torque and, worse, quoted a stall force the 70 A stator limit makes unreachable; it now prints the force the mechanism can actually produce.)*

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

**The negative-space rule we do keep from 254:** do not re-model every vendor knob. Rootstock's config models only what is (a) physical, (b) shared across vendors, or (c) required to derive a vendor knob. Everything else is reached through `applyRaw()`. `ControlConfig` has ~11 fields, not 40.

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
package org.rootstock.control;    // D1: ONE Gains, and it lives in .control, not .config.
                                   // Everything else in this block is org.rootstock.config.

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
    return Platform.isReal() ? real : sim;    // org.rootstock.core.compat, NOT RobotBase
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
 * a static initializer is a dead robot, §5.6), it carries the error, and RootstockRegistry
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
   *  AND appends a ConfigError to this config's error list (surfaced by RootstockRegistry). */
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
import org.rootstock.config.*;          // PositionConfig, MotorSpec, HomingStrategy, Setpoint, ...
import org.rootstock.units.*;           // LinearAxis, RotaryAxis
import org.rootstock.control.Gains;     // Gains is in control, NOT config
import org.rootstock.pure.units.Reduction;   // Reduction is in pure.units, NOT units

public final class RobotConfig {

  public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
      // --- what drives it -------------------------------------------------
      // .foc(true) enables FIELD-ORIENTED CONTROL on the voltage requests. It does NOT
      // change gain units -- gains stay volts-per-SI. (Torque-current output is a separate
      // field, .outputMode(OutputMode.TORQUE_CURRENT), and it is NOT in v0.1 -- see OQ 13.)
      .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                        .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
      // --- the gearbox: ONE place. Change this number and gains, sim, soft limits,
      //     Motion Magic constraints, and telemetry units ALL follow. ----------
      .reduction(Reduction.ofStages(3.0, 4.0))          // 12:1  -> 2.251 m/s free at the carriage
      // --- the geometry ----------------------------------------------------
      // 22 teeth x 0.250 in chain pitch x 2 cascade stages = 11.000 in = 0.279400 m per drum rot
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
          /* real */ Gains.pid(80.0, 0.0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33),
          /* sim  */ Gains.pid(150.0, 0.0, 0.0).withKv(5.00).withKa(0.06).withKg(0.33)))
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
| chain advance / drum rot | `22 teeth × 0.250 in` (one link per tooth) | 5.5000 in |
| kinematic drum radius | `5.5000 in ÷ 2π` | 0.87535 in = 0.0222339 m |
| *(geometric pitch dia, for reference)* | `0.25 in ÷ sin(π/22)` | 1.75669 in — its circumference, 5.51893 in, is **0.34 % larger** than the chain advance; Rootstock uses the advance |
| travel / drum rot | `5.5000 in × 2 stages` | **0.279400 m** (11.0000 in) |
| effective radius (incl. cascade) | `0.0222339 m × 2 stages` = `0.279400 ÷ 2π` | **0.0444679 m** |
| free speed | `5800 rpm ÷ 60 ÷ 12 × 0.279400` | **2.251 m/s** |
| cruise 1.6 m/s | `1.6 ÷ 2.251` = 71.1 % of free speed | no alert |
| soft max 55 in | `1.3970 m ÷ 0.279400` | **5.0000 drum rot** |
| kV | `≈ 12 V ÷ 2.251 m/s = 5.33`, less kS headroom | 5.00 V/(m/s) |
| kG | see the derivation below | **0.33 V** |
| Slot0.kP on the device | `80 V/m × 0.279400 m/rot` | 22.35 V/rot |
| Slot0.kD on the device | `2.0 V/(m/s) × 0.279400` | 0.5588 |

**kG, derived in full — because revision 3 printed 0.15 V and the stated inputs do not produce it.**

```
  carriage mass       24 lb x 0.45359237            =  10.8862 kg
  weight              10.8862 kg x 9.80665 m/s^2    = 106.756 N
  effective radius    0.279400 m / (2*pi)           =   0.0444679 m
                      (this ALREADY includes the 2x cascade -- that is what "effective" means)

  torque at the drum  106.756 N x 0.0444679 m       =   4.74731 N.m
  / 12:1 gearbox                                    =   0.395609 N.m at the rotor group
  / 2 motors                                        =   0.197805 N.m per rotor
  / kT 0.0194 N.m/A                                 =  10.1961 A per motor
  x R  0.0328 ohm                                   =   0.334433 V

                                                    ->  kG = 0.33 V
```

**What revision 3 did wrong:** it dropped the cascade `×2`. Using the bare pitch radius 0.0222339 m instead of the effective 0.0444679 m halves the torque and gives 0.167 V, which is 0.15 V once you also use the wrong free-speed-derived radius — i.e. **exactly the bug class `LinearAxis` exists to prevent, in the example written to demonstrate that it prevents it.** The row directly above it, the stall-force row, used the *correct* effective radius, so the table contradicted itself and nothing caught that either.

**Two honest caveats on the constants, since this table claims to derive rather than assert:**

* `kT = 0.0194 N·m/A` is right for a Kraken X60 in **both** commutation modes (FOC: 9.37 N·m ÷ 483 A; trapezoidal: 7.09 N·m ÷ 366 A — the same 0.0194). `R = 0.0328 Ω` is `12 V ÷ 366 A`, the **trapezoidal** figure, which is what WPILib's `DCMotor.getKrakenX60()` carries. This config sets `.foc(true)`, and `DCMotor.getKrakenX60Foc()` uses `12 ÷ 483 = 0.0248 Ω` — so **with FOC the same hold needs about 0.25 V**. The derived 0.33 V is therefore an **upper bound**, stated as one rather than quietly presented as the answer.
* Neither figure includes gearbox friction or chain drag, which on a real cascade elevator can be a larger term than either correction above. **kG is a measured quantity.** The wizard's kG bisection (`design/02` §6) is what produces the number a robot runs; this derivation exists so that when the wizard returns 0.41 V a student knows that is plausible and when it returns 3.0 V they know something is wrong.

Gains are illustrative starting points for the sim; on a real robot the tuning wizard measures kS/kV/kA and `FeedbackDesigner` produces kP/kD. The derivations are printed so a student can check them — and, as revision 4 demonstrates, so a *reviewer* can.

> **The same 0.15 V was replicated outside this document.** `DESIGN.md` §10A.1's *"a tuned elevator, for reference — these are 8793's numbers"* block carried it, and `design/02`'s tuning walkthrough consumes 8793's elevator numbers. Both were listed under `contractsRequested` for the reconciliation pass; this document could not fix them and did not pretend to have. *(Update, 2026-08-08, final verification: the request was fulfilled — `DESIGN.md` §10A.1 now reads `kG = 0.33 V` with this derivation reproduced figure for figure (review finding B6), and `design/02` was checked and carries no elevator `kG` literal at all. This note is kept as the record of the contract request.)*

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
    // the full 65.411:1. Phoenix fuses on-device, so Rootstock does NOT re-seed in Java.
    // D2b: rotorPerSensor x sensorPerOutput MUST equal reduction.rotorPerOutput(); Validation
    // makes a mismatch a FATAL ConfigError (§3.10). 65.411 x 1.0 == 65.411. ✔
    .feedback(new FeedbackSpec.FusedCancoder(
        /* id */ 23, /* bus */ "rio",
        /* magnetOffset */ Rotations.of(-0.1387),
        /* rotorPerSensor */ 65.411, /* sensorPerOutput */ 1.0))
    .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
    .hardStop(HardStop.REVERSE, SensorSpec.motorLimit(SensorSpec.Limit.REVERSE))
    .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
    // GAINS ARE VOLTS-PER-SI: for a rotary axis that means V/rad and V/(rad/s), NOT per degree.
    //   kP 5.0 V/rad  -> Slot0.kP  = 5.0  * 2*pi = 31.4 V/rot
    //   kV 1.25 V/(rad/s) -> Slot0.kV = 7.85
    //   kG 0.29 V     -> Slot0.kG  = 0.29, Arm_Cosine, GravityArmPositionOffset = -0.0
    // (Revision 4 rescaled all five from 34.126:1 to the true 65.411:1 -- see the derivation
    //  paragraph below. kG and kA scale as 1/G; kV scales as G; kP and kD track kA.)
    //  [INTENTIONAL-34.126] -- historical note; the LIVE gains above are the 65.411 ones.
    .gains(Gains.realOrSim(
        Gains.pid(5.0, 0.0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29),
        Gains.pid(10.0, 0.0, 0.0).withKv(1.25).withKa(0.010).withKg(0.29)))
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

**kG and kV derivation, printed by `describe()` — recomputed in revision 4 at the true 65.411:1.**

```
  arm mass            9.5 lb x 0.45359237           =  4.30913 kg
  centre of mass      21 in / 2 = 10.5 in           =  0.26670 m
  torque at the joint 4.30913 x 9.80665 x 0.26670   = 11.2699 N.m

  gear train          (58:10) x (58:18) x (42:12)   = 65.4111 : 1     <-- NOT 34.126  [INTENTIONAL-34.126]
  torque at the rotor 11.2699 / 65.4111             =  0.172291 N.m
  / kT 0.0194 N.m/A                                 =  8.88099 A
  x R  0.0328 ohm                                   =  0.291297 V     ->  kG = 0.29 V

  free speed          5800 rpm / 65.4111            =  88.670 output rpm
                                                    =  1.47784 output rot/s
                                                    =  9.28558 rad/s
  kV at full bus      12 V / 9.28558 rad/s          =  1.29233 V/(rad/s)
                      less kS headroom              ->  kV = 1.25 V/(rad/s)
```

**Why the other three gains moved too, since a reader will ask.** For a geared DC motor driving an inertia `J` at the output through reduction `G`, the rotor sees `τ_rotor = J·α / G`, so the volts needed per unit of output acceleration are `kA = J·R / (G·kT)` — **kA scales as 1/G**, exactly like kG. And the free output speed scales as `1/G`, so **kV scales as G**. Applying the ratio `65.411 / 34.126 = 1.9167`: `[INTENTIONAL-34.126]` — the "was" column of the table below is a historical record of the revision-3 gains and is not a live value.

| Gain | At 34.126:1 (revision 3) — historical `[INTENTIONAL-34.126]` | Scaling | At 65.411:1 (revision 4) — live |
|---|---|---|---|
| `kG` | 0.56 V | `× 1/1.9167` | **0.29 V** (0.292 — matches the independent derivation above ✔) |
| `kA` | 0.02 V/(rad/s²) | `× 1/1.9167` | **0.010** |
| `kV` | 0.65 V/(rad/s) | `× 1.9167` | **1.25** (1.246 — matches ✔) |
| `kP` | 9.5 V/rad | tracks kA (for a fixed closed-loop ωₙ, `kP ≈ kA·ωₙ²`) | **5.0** |
| `kD` | 0.35 V/(rad/s) | tracks kA (`kD ≈ 2ζωₙ·kA`) | **0.18** |
| `kS` | 0.20 V | friction-dominated, does **not** scale cleanly with G | **0.20**, unchanged and explicitly not derived |

`kG` and `kV` reproduce independently from the physics *and* from the scaling law, which is the check that matters. `kP`/`kD` are **illustrative sim starting points** carried through the same factor so the example stays self-consistent; on hardware `FeedbackDesigner` produces them and `kS` is measured. `DESIGN.md` §16 item 1 prescribes exactly kG = 0.29 V and kV = 1.25 V/(rad/s) at 65.411:1, and this derivation agrees with it to three figures.

> **A dimensional bug in D2c's shorthand, flagged rather than silently worked around.**
> `DESIGN.md` D2c describes the `UNTUNED` sim seed as *"kG from mass·g·r / (G·kT)"*. Check the
> units: `[kg · m/s² · m] / [1 · N·m/A]` = `N·m / (N·m/A)` = **amperes**, not volts. The current is
> only half the derivation; it becomes a voltage when multiplied by the winding resistance, which
> the shorthand drops. The correct form is the one `design/02` §6.5 already carries and the one
> this section derives above:
>
> ```
>     kG [V] = m·g·r·R / (G · kT · n)          n = motor count, R = winding resistance
> ```
>
> — with the further caveat, noted in `REVIEW.md` M8, that if `PlantPrior` is handed an
> *n*-motor `DCMotor` (`DCMotor.getKrakenX60Foc(2)`), WPILib has **already** scaled `rOhms` by
> `1/n` while leaving `kT` unchanged, so `kT/R` carries the ×n and an *explicit* `÷ n` on top makes
> the prior n× low. This document's derivations divide by the motor count exactly once, against a
> single-motor `kT`/`R` pair, which is consistent. `DESIGN.md` D2c's shorthand is listed under
> `contractsRequested`; this document does not implement it and cannot fix it here.

**A residual honesty note.** These numbers describe an arm this maintainer has not built. Per **D2c** the preferred form for a *quickstart* is `Gains.UNTUNED`, which refuses closed loop on hardware and derives a physics prior in sim. This is not a quickstart — it is the config reference, and a reference that prints `NaN` for every gain teaches nothing about magnitude. So it keeps literals, and §5.6's Tier-3 checklist plus the boot dump's *"these gains were derived from your declared mass, not measured"* line carry the warning instead.

Note what is **absent**: no `/360.0`, no `GravityTypeValue`, no `SensorToMechanismRatio`, no `RotorToSensorRatio` arithmetic, no `SingleJointedArmSim` construction, no `MotionMagicCruiseVelocity` in rotations, no soft limits in rotations, no `Math.max/Math.min` ordering hack, no `GravityArmPositionOffset` sign reasoning. Each of those exists by hand in at least one of the user's three repos.

### 5.6 Validation: what happens when a value is wrong

**Nothing throws from a static initializer. Ever.**

Revision 1 had Tier-1 validation throw from the record's compact constructor while every example declared configs as `public static final` fields. The result on a real robot: `ExceptionInInitializerError` from `frc.robot.RobotConfig.<clinit>`, robot code never starts, the driver station shows red "Robot Code", and the beautifully written `ConfigError` message is a *cause* nested under a stack trace whose top frames are JVM class-init machinery. Revision 1 also claimed these are *"caught before `robotInit` finishes... never on the field"* — false. A student editing a soft limit at an event hits exactly this, with a dead robot and no obvious message, and there was no safe mode.

The pipeline is now:

```
compact constructor    -> pure, local, non-throwing; STORES List<ConfigError>
RootstockRegistry.addAll -> collects errors from every config,
                          runs the CROSS-config checks (CAN IDs, setpoint names, bus budget),
                          prints ALL of them at once,
                          and if any are present enters SAFE_MODE
RootstockLifecycle       -> init() COMPLETES either way; the robot BOOTS
   (reached via RootstockRobot, or wired by hand from the team's own LoggedRobot -- §1.1a.
    D29 renamed this method from robotInit(); D13a deleted RootstockRobot's override of the
    WPILib hook of that name. Neither rename changes the property this diagram states.)
```

```java
package org.rootstock.config;

/** A VALUE, not an exception. Carries everything the message needs. */
public record ConfigError(Severity severity, String owner, String field, String value,
                          String expected, String explanation, String declaredAt) {
  public enum Severity { FATAL, WARNING, PLACEHOLDER }
}
```

```java
package org.rootstock.mechanism;

public final class RootstockRegistry {
  public void addAll(Object... registrables) {
    List<ConfigError> all = new ArrayList<>();
    for (Object o : registrables) all.addAll(configErrorsOf(o));         // per-config, local
    all.addAll(CanIdRegistry.scanForConflicts(resolvedSpecs(registrables))); // ONE global scan
    all.addAll(SetpointNameChecker.check(registrables));                 // §8.9
    all.addAll(Validation.crossChecks(registrables));                    // bus budget, etc.

    Validation.printAll(all);                     // ALL of them, once -- not one per deploy cycle
    // CRITICAL tier: a FATAL config list has to survive an FMS-attached log, which is exactly
    // where a student will be reading it from. `design/04` §2.3 shape: critical(String, String[]).
    //
    // String[], NOT a struct array. Revision 4 wrote `RootstockLog.put(..., toStructArray(all))`,
    // which was wrong twice over: put(...) does not exist in `design/04` §2.3 (the owning
    // surface), and ConfigError's six fields are all Strings, so it CANNOT be StructSerializable
    // -- a WPILib struct is fixed-size by definition and there is no legal Struct<ConfigError>.
    // `Validation.toStrings` renders the same list `printAll` writes to the console, one entry
    // per error, so the NT topic and the riolog say the identical thing.
    RootstockLog.critical("/Rootstock/Config/Errors", Validation.toStrings(all));

    if (all.stream().anyMatch(e -> e.severity() == FATAL)) enterSafeMode(all);
  }
}
```

**SAFE_MODE, in full.** When a FATAL config error exists:

* `RootstockLifecycle.init()` **completes**, whether it was reached through `RootstockRobot`, called explicitly at the end of the team's constructor, or run lazily by the first `beforeUserPeriodic()` (§1.1a; D29 named this method `robotInit()` through revision 5). The robot boots, connects to the driver station, and appears on the dashboard.
* The `CommandScheduler` runs. Telemetry runs. Alerts publish. `describe()` still works.
* **Every mechanism is forced to `MechanismMode.NEUTRAL` and refuses every command.** `goTo`, `manual`, `home`, `setVoltage` return `Commands.none()` with a named alert. Nothing moves.
* `/Rootstock/Driver/SafeMode` is `true`, and `/Rootstock/Driver/SafeModeErrors` carries the full structured list.
* A persistent `Alert.kError` names the count and the first error verbatim, so the driver station shows a sentence rather than an exception.
* `rootstock doctor` prints the whole list with fixes.

> A misconfigured robot boots, connects, and tells you what is wrong — instead of showing red
> "Robot Code" with nothing. That is the difference between a five-minute fix in the pits and a
> lost match.

**Tier 1 — structurally impossible: FATAL, collected, robot boots into SAFE_MODE.**

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Elevator" (PositionConfig)

  field    reduction
  value    0.0 (rotor rotations per output rotation)
  expected > 0

  A reduction is how many times the MOTOR turns for one turn of the OUTPUT shaft.
  A 12:1 gearbox is Reduction.of(12.0), not Reduction.of(1.0/12.0).
  If you know the tooth counts, prefer Reduction.ofTeeth(58, 10).then(58, 18).

  declared at frc.robot.RobotConfig.<clinit>(RobotConfig.java:41)
```

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Arm" (PositionConfig)

  field    limits.min = 95.0 deg
  field    limits.max = 10.0 deg
  expected limits.min < limits.max

  These look swapped. Rootstock will not order them for you, because on a mechanism with
  an inverted motor "min" and "max" are a real physical claim about which way is positive.
  Check describe() -- it prints which direction is positive -- then fix the call.

  declared at frc.robot.RobotConfig.<clinit>(RobotConfig.java:78)
```

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Arm" (PositionConfig)

  field    control.gravity = COSINE
  field    axis.horizontalAt = <not set>

  Cosine gravity compensation needs to know WHERE the mechanism is horizontal, or kG will be
  applied with the wrong sign over half the range. Use RotaryAxis.arm(Degrees.of(<angle at
  which the arm is level>)). If your encoder reads 0 with the arm level, that is Degrees.of(0).
```

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Arm" (PositionConfig)

  field    axis.horizontalAt = 95.0 deg  ->  0.2639 output rotations
  expected within +/-0.25 rotations (+/-90 deg) of the mechanism zero

  Phoenix Slot0Configs.GravityArmPositionOffset accepts only (-0.25, 0.25) rot. A larger
  offset is clamped by the device with NO error, so kG would be applied at the wrong angle
  over the whole range: the arm sags on one side and slams on the other.

  Fix: re-zero the CANcoder magnet offset so the encoder reads NEAR 0 with the arm level
  (`rootstock zero Arm` captures it for you), then RotaryAxis.arm(Degrees.of(0)).
  Alternative: ControlLocation.RIO_FULL -- WPILib's ArmFeedforward has no offset limit --
  and Rootstock will tell you it downgraded.
```

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Wrist" (PositionConfig)

  field    motors.leader.canId = 64
  expected 1..62 for a Phoenix 6 device

  Constructing a Phoenix device with an out-of-range ID throws and prevents robot code from
  starting. (This exact value is documented as a past field failure in
  9143-2025-A-Updated/src/main/java/frc/robot/Constants.java.)
  The robot has booted into SAFE_MODE so you can read this message on the driver station.
```

```
org.rootstock.config.ConfigError [FATAL]: Rootstock config error in "Elevator" (PositionConfig)

  field    motors.leader.outputMode = TORQUE_CURRENT
  expected VOLTAGE  (the only supported output mode)

  Rootstock gains are VOLTS-per-SI. Torque-current gains are AMPS-per-SI and the tuning
  wizard cannot yet convert them.  If you wanted FOC's extra torque, that is the separate
  and already-enabled switch:  MotorSpec.talonFX(20, "rio").foc(true)
```

### 5.6b Cross-config checks happen exactly once, globally

Revision 1 put the CAN-ID conflict check inside the compact constructor via a `CanIdRegistry` side effect. But **every `with*()` copy re-runs that constructor**, so the per-robot overlay pattern in `design/06 §7.3` — the design's own answer to the 9143 A/B sibling-robot problem — would register CAN ID 22 twice and throw a false "CAN ID conflict" on a *correct* config.

All global state is therefore removed from record constructors. The duplicate-ID scan runs once, in `RootstockRegistry.addAll(...)`, over the **final resolved** config set:

```
org.rootstock.config.ConfigError [FATAL]: Rootstock CAN ID conflict

  device id 22 on bus "rio" is claimed by BOTH:
    - "Arm" leader        (TalonFX)   declared at RobotConfig.java:76
    - "Intake" leader     (TalonFX)   declared at RobotConfig.java:103

  Two devices with the same ID on the same bus will fight. Renumber one in Phoenix Tuner X
  AND here.
```

Release-blocking test: `WithCopyDoesNotDoubleRegisterTest` asserts that
`ELEVATOR.withGains(g).withReduction(r).withMotors(m)` produces zero errors and that
`RootstockRegistry.addAll(that)` reports no conflict.

**Tier 2 — physically implausible: a persistent `Alert`, plus a log entry. Never fatal.** The robot still runs; the team can drive.

```
[Rootstock][WARN] Elevator: constraints.maxVelocity = 4.00 m/s, but the free-speed estimate
  is 2.251 m/s. Computed from 2 x Kraken X60 (5800 rpm free with FOC) through 12.000:1 into a
  22-tooth #25 sprocket (5.500 in of chain per drum rotation) with 2 cascade stages
  (0.279400 m of carriage travel per drum rotation). Motion Magic will never reach this cruise
  velocity, so profiles will effectively be acceleration-limited only.
  Suggested: <= 1.80 m/s (80% of free speed).
```

```
[Rootstock][WARN] Arm: control.gains.kG = 0.00 V with GravityMode.COSINE. The arm will sag.
  Procedure: disable, hold the arm horizontal, enable, raise kG until the arm just holds
  station with kP = 0. Live-tunable at /Tuning/Arm/kG. Expected magnitude for a 9.5 lb arm
  at 21 in through 65.411:1 is about 0.29 V.
```

```
[Rootstock][WARN] Elevator: control.tolerance = 0.500 in is SMALLER than one loop step at
  cruise -- at 1.60 m/s the carriage moves 0.0320 m (1.260 in) per 20 ms, 2.5 tolerance bands
  per loop. The mechanism can never be OBSERVED inside the tolerance band while it is still
  moving fast, so atGoal() only latches after the profile decelerates. That is correct, and
  Rootstock's velocity gate (|v| <= 0.050 m/s) enforces it -- this warning exists so
  "atGoal took longer than I expected" is already explained.
  If you want an earlier release, use atSetpoint() or a Superstructure earlyRelease
  predicate. Do NOT widen the tolerance.
```

```
[Rootstock][WARN] Elevator: control.useExpo() is true but gains.kV = 0.00 and gains.kA = 0.00.
  Motion Magic Expo shapes its profile ENTIRELY from measured kV and kA; with them at zero
  Rootstock would hand the device CTRE's factory defaults (0.12 V/rps, 0.1 V/rps^2).
  Fix: run `rootstock tune Elevator --feedforward`, or set .useExpo(false) and use trapezoid
  constraints.
```

```
[Rootstock][WARN] Wrist: HomingStrategy.assumeAtBoot(Degrees.of(90)) assumes the mechanism
  is resting on a known hard stop every time the robot powers on. There is no sensor
  confirming this. If the wrist can be moved by hand while disabled, position will be wrong
  and cosine gravity compensation will push the wrong way.
  Fix: add an absolute encoder (FeedbackSpec.SparkAbsolute / FusedCancoder) or a limit
  switch (HomingStrategy.limitSwitch(...)).
```

That last one directly addresses the boot-seeding assumption baked into six IOs in `0000-XXXX-Robot-Template` (`ArmIOTalonFX.java:76`, `WristIOTalonFX:66`, `IntakeIOTalonFX:48`, `TurretIOTalonFX:62`, `ClimberIOTalonFX:60`, `ElevatorIOTalonFX:83-84`) — the template documents the assumption in a comment; Rootstock says it out loud, every boot, on the driver station.

**Tier 3 — placeholder detection.** Any config field left at a Rootstock sentinel (`Gains.UNTUNED`, `Reduction.UNMEASURED`) produces a boot-time checklist, reproducing the `ADD`/`VERIFY`/`TUNE` convention of `0000-XXXX-Robot-Template/Constants.java` as machine-checkable state instead of a comment:

```
[Rootstock] First-setup checklist -- 3 values still at placeholders:
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
    String rootstockVersion, String wpilibVersion, String vendorLibVersion
) implements StructSerializable {
  public static final Struct<MechanismConfigSnapshot> struct =
      StructGenerator.genRecord(MechanismConfigSnapshot.class);
}
```

Published to `/Rootstock/Config/<name>` and to the WPILOG. This makes "is it a bug in the library? a regression between versions?" answerable from a log file alone — the exact question `web-smallteam.json` names as the debuggability acceptance test. `subscribedSignals` in particular makes a `NaN` field self-explaining from the log.

---


## 6. Brief item 2 — Mechanism templates

### 6.1 `Mechanism` — the base

```java
package org.rootstock.mechanism;

import edu.wpi.first.wpilibj2.command.Subsystem;   // 2027: org.wpilib.commands2.Subsystem
import org.rootstock.telemetry.RootstockLog;
import org.rootstock.tuning.TunableGains;

/**
 * Base for every Rootstock mechanism.
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
public abstract class Mechanism implements Subsystem, TelemetrySource {
  protected final String          m_name;
  protected final MechanismUnits  m_units;
  protected final MotorIO         m_io;
  protected final MotorInputs     m_inputs = new MotorInputs();
  /** D9 (revision 5): there is no TelemetrySink field, and there is no TelemetrySink TYPE
   *  anywhere in Rootstock -- the telemetry-side one was struck along with
   *  TelemetrySource.sample(). CORE writes through the RootstockLog statics from periodic()
   *  (§6.2), which is the PUSH half of the contract.
   *
   *  The DECLARATION half is the other half of the same contract, and it is now declared:
   *  this class `implements TelemetrySource` (see the class header above), `telemetryName()`
   *  returns m_name, and `describe(TelemetryDescriptor)` is called ONCE from
   *  RootstockRegistry.addAll (D27). Full specification, including the five extra(...) keys, the
   *  Degrees-not-Radians divergence and the two contract requests it raises, is in §1.1b.
   *  (This javadoc carried an OPEN CONTRACT ITEM marker through revision 5, when the
   *  reconciliation pass closed the call sites and left the declaration undone -- `DESIGN.md`
   *  §16 item 5(b). The marker is gone because the item is done, not because it was tidied.)
   *
   *  D11: the tunable handle is the concrete TunableGains, not a TunableGroup. */
  protected final TunableGains    m_tunables;

  // ---------- log keys, precomputed ONCE in the constructor ----------
  // Rev 1 built every key by concatenation inside periodic():
  //     m_log.putDouble("/Rootstock/" + m_name + "/Goal", m_goal)
  // -- six String allocations plus StringBuilder churn per mechanism per loop. Four mechanisms
  // at 50 Hz is 1,200 String allocations/second, in direct violation of principle 9 and of
  // DESIGN.md §1.7 constraint 9's "zero bytes allocated in periodic() after warmup".
  protected final String kInputs, kGoal, kSetpoint, kSetpointVelocity, kMeasured,
                         kError, kGoalError, kAtGoal, kAtSetpoint, kMode,
                         kFeedbackVolts, kFeedforwardVolts, kActiveProfile, kOpenLoopClamped,
                         kDeviceResets;

  protected Mechanism(String name, MechanismUnits units, MotorIO io, TunableGains tunables) {
    m_name = name; m_units = units; m_io = io; m_tunables = tunables;
    String base       = "/Rootstock/" + name + "/";
    kInputs           = base + "Inputs";
    kGoal             = base + "Goal";
    kSetpoint         = base + "Setpoint";
    kSetpointVelocity = base + "SetpointVelocity";
    kMeasured         = base + "Measured";
    // Error and GoalError are TWO DIFFERENT KEYS with two different formulas, and `design/04` §3.1
    // is the authority: Error = Setpoint - Measured (CRITICAL); GoalError = Goal - Measured
    // (STANDARD). Revision 3 published one key named Error carrying the GOAL formula, so every
    // in-flight profiled move looked like a huge error on the §5.5 triage plot and in the
    // tuning layouts, which read Error against the profile setpoint frame by frame.
    kError            = base + "Error";
    kGoalError        = base + "GoalError";
    kAtGoal           = base + "AtGoal";
    kAtSetpoint       = base + "AtSetpoint";
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
  /** The leader + followers + inversion this mechanism was configured with. PROTECTED, not
   *  public: its only consumer is describe(TelemetryDescriptor)'s motorCount() line (§1.1b),
   *  and a public accessor would invite a team to reach past MotorIO. MotorGroup.count() is
   *  `1 + followers.size()`. Reachable for the same reason geometry() below is `final` --
   *  the base class already reads the config. */
  protected final MotorGroup motorGroup();
  public final MotorInputs inputs();            // read-only view for a Superstructure
  public final TunableGains tunables();

  /** Called by CommandScheduler if registered, or by you if not. Idempotent within a loop.
   *  Wraps its body in try/catch(Throwable): a mechanism that fails raises
   *  <name>/periodic-threw, goes neutral, and the scheduler keeps running. Degrade, never crash. */
  @Override public abstract void periodic();

  // NOTE (D18): there is NO simulationPeriodic() on Mechanism. Revision 3 declared one here
  // and shipped a body in §6.7 that called m_io.simulatedMotorVoltage() and
  // m_io.updateSimulatedSensors(...) -- two methods that appear on no interface in this
  // document, so it could not have compiled. Binding decision D18 deleted the method and both
  // call sites: CORE declares a MechanismGeometry, the backend exposes MotorIO.simHandle(),
  // and RootstockSim owns and steps the plant. See §1.4 and §6.7.

  /** The plant declaration D18 requires. Pure data, computed once from PositionConfig + Axis;
   *  handed to the MechanismGeometrySink of org.rootstock.core.spi at registration. */
  public final MechanismGeometry geometry();

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
package org.rootstock.mechanism;

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
   *  (ShooterSubsystem.java:456-462). Rootstock keeps these two verbs apart forever. */
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
  RootstockLog.processInputs(kInputs, m_inputs);

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
    RootstockTracer.enter("Mechanism/ApplyGains");
    m_io.applyGains(m_tunables.gains());              // volts-per-SI
    m_io.applyConstraints(m_tunables.constraints());  // user units per s^n
    RootstockTracer.exit("Mechanism/ApplyGains");
  }

  // 3. Absolute-encoder guarded re-seed (only for non-fused sources, only when idle).
  m_absoluteSeeder.maybeReseed(m_mode, m_inputs, m_io);

  // 4. Disabled / SAFE_MODE: never hold a stale setpoint across a disable->enable edge.
  if (MatchContext.isDisabled() || safeMode()) {    // §16 item 3: never DriverStation directly
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
  //
  //    ERROR vs GOAL ERROR. `design/04` §3.1 publishes these as two distinct keys with two distinct
  //    formulas, and this is the schema every tuning layout and the §5.5 triage workflow read:
  //        Error     = Setpoint - Measured   (CRITICAL)  -- how well are we TRACKING right now
  //        GoalError = Goal     - Measured   (STANDARD)  -- how far is there left to go
  //    Revision 3 published a single Error carrying the GOAL formula, which makes every
  //    in-flight profiled move look like a tracking catastrophe on the plot the wizard uses to
  //    judge tracking. For ON_MOTOR_* locations m_setpointUser == m_goal (the device owns the
  //    profile and we cannot see its internal reference unless CLOSED_LOOP_REFERENCE is
  //    subscribed), so the two curves coincide -- and that coincidence is itself informative,
  //    which is why both keys are published on every mechanism rather than conditionally.
  //
  //    TIER IS THE METHOD NAME. `design/04` §2.3 owns this surface and has NO put(...):
  //    critical(...) publishes at CRITICAL, log(...) at STANDARD. Every tier below is
  //    `design/04` §3.1's Outputs table, read off it key by key. No Demotable argument
  //    appears here because §3.1 states outright that none of these outputs are demotable.
  //    Units are NOT passed per call -- each key's unit is declared once at registration
  //    through TelemetryDescriptor (`design/04` §1.1); see the note under §1.1.
  RootstockLog.critical(kGoal,             m_goal);                       // §3.1 CRITICAL
  RootstockLog.critical(kSetpoint,         m_setpointUser);               // §3.1 CRITICAL
  RootstockLog.log     (kSetpointVelocity, m_setpointVelUser);            // §3.1 STANDARD
  RootstockLog.critical(kMeasured,         m_measured);                   // §3.1 CRITICAL
  RootstockLog.critical(kError,            m_setpointUser - m_measured);  // §3.1 CRITICAL
  RootstockLog.log     (kGoalError,        m_goal - m_measured);          // §3.1 STANDARD
  RootstockLog.critical(kAtGoal,           m_atGoal);                     // §3.1 CRITICAL
  RootstockLog.critical(kAtSetpoint,       m_atSetpoint);                 // §3.1 CRITICAL
  RootstockLog.log     (kMode,             m_modeName);                   // §3.1 ControlMode, STANDARD
                                                                        //   MechanismMode.name(),
                                                                        //   cached per enum constant
  // Not in §3.1's fixed Outputs table -- declared as an extra(...) key on this mechanism's
  // TelemetryDescriptor, on the UNIT-FREE overload at CRITICAL tier. long, not double:
  // `design/04` §2.3 has critical(String, long), and a reset COUNT is an integer. Revision 4
  // cast it to double for a put(...) that never existed.
  //
  // THIS LINE IS HALF OF A PAIR (2026-08-08). It was RootstockLog.log(...) -- STANDARD -- to match
  // §1.1b's earlier STANDARD declaration. `design/04` §3.1 overruled the tier to CRITICAL: a
  // controller that rebooted mid-match is the textbook "explain a lost match" signal, and it is
  // readable only after the fact off a robot that has since been power-cycled. §1.1b's
  // d.extra("DeviceResetCount", Tier.CRITICAL) moved in the same edit. `design/04` §1.5's schema
  // audit compares declaration against publish and fails if only one of the two moves -- so if
  // you are changing one of these lines, you are changing both.
  RootstockLog.critical(kDeviceResets,     (long) m_inputs.deviceResetCount);
}
```

`publishesTheStandardSchema` (`design/04`'s contract test) asserts that a `PositionMechanism` publishes **both** `Error` and `GoalError` under `/Rootstock/<name>/`, with `Error` computed from the setpoint. A regression to one key, or to the wrong formula, fails CI.

Logging setpoint, measurement, error, and applied output for *every* loop of *every* mechanism, automatically, is the direct answer to the "abstractions hide the intermediate data" criticism: Rootstock makes those signals **more** visible than hand-written code, not less. And it does so at zero allocation — the CI allocation test runs against the full §9 example robot and is an **M5 gate condition**, re-checked at M24 (the dated gates G0–G5 are deleted).

**`applyClosedLoop()` — where the four control locations diverge, and only here:**

```java
private void applyClosedLoop() {
  double goalRot = m_units.toOutputRotations(m_goal);
  MotionConstraints active = activeConstraints();       // constraint profiles, below

  switch (m_config.control().location()) {

    case ON_MOTOR_PROFILED, ON_MOTOR_DIRECT -> {
      // Gravity is computed ON the controller (Phoenix kG + GravityType; REV kG/kCos),
      // so the arbitrary feedforward is zero. Latching + heartbeat live inside the IO.
      // The velocity term carries the field-locked turret's counter-rotation (§6.4). The
      // MECHANISM does not care how the backend delivers it -- capabilities()
      // .positionGoalVelocity() records which VelocityCarrier is in use, and every value
      // except UNSUPPORTED delivers the term. Only RIO_VOLTAGE_TRIM needs help from here,
      // and that help is a voltage, not a silent drop.
      double goalRps = m_units.toOutputRps(fieldLockVelocityUserPerSec());
      double trimVolts = (m_io.capabilities().positionGoalVelocity()
                          == VelocityCarrier.RIO_VOLTAGE_TRIM)
                       ? m_kVDevice * goalRps      // same formula, computed one level up
                       : 0.0;
      if (active == m_baseConstraints) {
        m_io.setPositionGoal(goalRot, goalRps, trimVolts);
      } else {
        m_io.setPositionGoal(goalRot, goalRps, trimVolts, active);  // DynamicMotionMagicVoltage
      }
      m_profileFinished = true;
      m_setpointUser    = m_goal;
      m_setpointVelUser = m_units.toUserPerSec(goalRps);
    }

    case RIO_PROFILE_MOTOR_LOOP -> {
      // 254 / 4738 pattern: step our own profile, hand the STEP to the on-board position loop.
      // The profile runs in SI so its constraints and the gains agree; only the seam call
      // converts back to output rotations.
      TrapezoidProfile.State goalState =
          new TrapezoidProfile.State(m_units.toSi(m_goal), 0.0);
      m_siProfileState = siProfile(active).calculate(Clock.dt(), m_siProfileState, goalState);
      m_setpointUser    = m_units.fromSi(m_siProfileState.position);
      m_setpointVelUser = m_units.fromSiPerSec(m_siProfileState.velocity);
      m_io.setPositionGoal(m_units.toOutputRotations(m_setpointUser),
                           m_units.toOutputRps(m_setpointVelUser),
                           0.0);
      m_profileFinished = siProfile(active).isFinished(0.0);
    }

    case RIO_FULL -> {
      // ===================================================================================
      // THIS IS THE ONE PLACE IN ROOTSTOCK WHERE SI CONVERSION HAPPENS.
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

      m_io.setVoltage(RootstockMath.clamp(fb + ff, -12.0, 12.0));
      m_profileFinished = p.isFinished(0.0);
      m_setpointUser    = m_units.fromSi(cur.position);
      m_setpointVelUser = m_units.fromSiPerSec(cur.velocity);

      // STANDARD tier. The fb/ff split is not in `design/04` §3.1's fixed Outputs table (that
      // table carries the combined `Output`); both are declared as extra(...) keys on this
      // mechanism's TelemetryDescriptor. `design/04` §2.3 shape: log(String, double).
      RootstockLog.log(kFeedbackVolts, fb);
      RootstockLog.log(kFeedforwardVolts, ff);
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

// The ONE place a volts-per-output-rotation kV is derived above the seam, for the
// RIO_VOLTAGE_TRIM branch of the field-lock path. Identical formula to the one every backend
// uses for Slot0.kV / FeedForwardConfig.kV (§3.5.4 step 6), so there is exactly one way to be
// wrong and one test that catches it (UnitsContractTest). Recomputed on every applyGains().
m_kVDevice = m_config.control().gains().kV() * m_units.siPerOutputRotation();
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
[Rootstock][WARN] Elevator: addConstraintProfile("gentle", ...) needs runtime profile changes,
  but DynamicMotionMagicVoltage requires Phoenix Pro AND a CANivore bus, and this TalonFX
  (CAN 20) is on bus "rio". Switching Motion Magic constraints would require a blocking CAN
  config write from periodic(), which Rootstock will not do.
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
2. **In Java**, before every goal — `RootstockMath.clamp(goal, min, max)`, so the limit holds even if the device config failed to apply, and so the clamp can be *reported*.
3. **Hard stops**: `SensorSpec.motorLimit(...)` wires the controller's own limit-switch input (firmware stop, zero latency) **and subscribes the corresponding limit `StatusSignal`** (§3.5.1), so `forwardLimitTripped` is a real reading rather than a frozen `false`. A DIO switch is checked in `periodic()` and zeroes output that loop, with a config-time warning naming the latency difference.
4. **Open-loop clamp**: `clampOpenLoopAgainstSoftLimits()` zeroes a manual/open-loop command that pushes past a soft limit, which the device's soft limit would do anyway — but doing it in Java means a student pushing the stick sees `/Rootstock/Arm/OpenLoopClamped = true` instead of "the stick stopped working".

### 6.3 Homing / zeroing

```java
package org.rootstock.mechanism;

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
   *  implicitly; Rootstock makes it an explicit, audible choice. */
  record AssumeAtBoot(double seedToUserUnits) implements HomingStrategy {}

  /** Try in order; first success wins. e.g. absolute encoder, else current-spike. */
  record Composite(List<HomingStrategy> strategies) implements HomingStrategy {}

  enum Direction { FORWARD, REVERSE }
}
```

Guarantees the library provides for all of them — **this is a mechanism-damage surface, so the guarantees are hard**:

* Homing runs in `MechanismMode.HOMING`; soft limits are **suspended** during homing (you are deliberately driving into a stop) and hard limits are **not**.
* `CurrentSpike.volts` is **magnitude-clamped to 3.0 V** and the stator current limit is **temporarily reduced to `currentThresholdAmps × 1.5`** for the duration of the routine, so a mis-signed direction pushes gently rather than destructively. Both clamps are reported in `describe()`.
* **Both of those are device config writes made while enabled, and revision 4 specifies exactly how they happen — revision 3 did not.** They go through **`applyVerified`**, which §3.9's legal-caller list now names homing-start and homing-completion for, alongside the reset-recovery exception it already granted. The transaction is:

  ```
    HOMING START      applyVerified(SoftwareLimitSwitchConfigs{Forward/ReverseEnable = false})
                      applyVerified(CurrentLimitsConfigs{StatorCurrentLimit = threshold * 1.5})
                      -> either returns false  =>  ABORT before any voltage is commanded.
                         The mechanism never moves, isHomed() stays false, and
                         <name>/homing-timed-out is raised with reason CONFIG_APPLY_FAILED.
                         Refusing to start is free; starting with an unknown current limit is not.

    ... routine runs, seed, back off ...

    HOMING COMPLETION applyVerified(CurrentLimitsConfigs{original})
                      applyVerified(SoftwareLimitSwitchConfigs{original, enabled})
                      -> either returns false  =>  setNeutral(), isHomed() stays FALSE, and
                         <name>/homing-limits-unrestored latches (MatchImpact.BLOCKS_MATCH)
                         with the message in §3.9.
  ```

  The alternative — `applyFast`, fire-and-forget, no read-back — is what revision 3's silence forced, and a single lost restore frame would leave device soft limits **disabled for the rest of the enable period**, until the next `disabledInit()` verified re-apply, on a current-spike-homed elevator with no absolute encoder. That is the mechanism class least able to tolerate it, in the section that opens by promising the guarantees are hard. Homing is bounded (at most once per enable, in practice once per boot), it happens while the mechanism is already crawling at ≤3.0 V into a stop, and it is traced under `Mechanism/HomingConfig` with a 300 ms budget, so the blocking cost is both affordable and visible.
* **Java-side clamping is unaffected by the suspension.** `PositionMechanism` clamps every commanded goal against the configured soft limits in Java (§6.2 layer 2), and that clamp is *not* suspended during homing — only the device-side firmware limit is, and only for the axis being driven into its stop. So even in the failure case above, a *commanded* goal still cannot exceed the limits; what is lost is the backstop that works when robot code hangs, which is why the alert is `BLOCKS_MATCH` rather than `PIT_ONLY`.
* A homing timeout raises `<name>/homing-timed-out` **and leaves `isHomed()` false**, so `atGoal()` stays false and a superstructure interlock can refuse to run. Silent failure is never an option.
* Homing **aborts immediately** on: a device disconnect, a device reset, a follower disagreement, the opposite-direction limit switch asserting, or `MatchContext.isDisabled()`. Every abort names its cause.
* After a successful current-spike home, the mechanism backs off `backoffUserUnits` before seeding, so it is not resting on the stop with kG fighting it.
* `seedPosition` uses the non-blocking `setPosition(value, 0.0)` form. Always.
* Homing while enabled only. `homeCommand()` is not `ignoringDisable`.
* Homing **refuses to start in SAFE_MODE**.
* Sim: `CurrentSpike` works in simulation because `ElevatorSim`/`SingleJointedArmSim` report a current spike at their travel limits, so the routine is testable off-robot. This is the entire reason homing lives in the library rather than in robot code.

Telemetry: `/Rootstock/<name>/Homing/{Active,Strategy,ElapsedSec,TriggerValue,Succeeded,AbortReason,LimitsSuspended,LimitsRestoreVerified}`. The last two exist so "were the soft limits actually put back?" is answerable from a log file alone, which is the question the failure above makes urgent.

Release-blocking test, added in revision 4: **`HomingRestoresLimitsTest`** — run a `CurrentSpike` home in simulation with a `MotorIO` stub whose `applyVerified` returns `false` on the *restore* call only, and assert that the mechanism goes neutral, `isHomed()` is false, `<name>/homing-limits-unrestored` is active with `MatchImpact.BLOCKS_MATCH`, and `Homing/LimitsRestoreVerified` is `false` in the log.

### 6.4 Continuous / over-360 axes, and the field-locked turret

`8793-2026-Robot/.../ShooterSubsystem.java:349-369` contains a correct, subtle turret unwrap: track the last commanded angle, take the shortest delta across the ±180° discontinuity, and if the unwrapped target would exceed a physical limit, wrap 360° to the other side of the >360° travel range, then clamp. That is universal for any azimuth with more than one turn of travel, and it is exactly what a team writes at 2 am and never revisits.

Rootstock owns it:

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

**How the term reaches each device — corrected in revision 4, because revision 3 named a method that does not exist.**

Revision 3 said this *"reaches the device as `PositionVoltage.withVelocity(...)` / `MotionMagicVoltage.withVelocity(...)`"*. The first is real; **the second is not** — `MotionMagicVoltage` has no `Velocity` field and no `withVelocity` method (verified; see the table in §3.5.6). So the sentence that claimed Rootstock fixes `TurretIONeo`'s silent drop described a code path that would not compile, on the *default* `ControlLocation` for a Kraken turret. The corrected mapping, and the `VelocityCarrier` each backend reports:

| Backend / `ControlLocation` | Carrier | Mechanism |
|---|---|---|
| Phoenix, `ON_MOTOR_DIRECT` or `RIO_PROFILE_MOTOR_LOOP` | `NATIVE_REQUEST_FIELD` | `PositionVoltage.withVelocity(rps)` — a genuine velocity **reference** inside the device's closed loop. *"Velocity to drive toward in rotations per second"* (verified). |
| Phoenix, `ON_MOTOR_PROFILED` (Motion Magic, Expo, or Dynamic) | `DEVICE_FEEDFORWARD_VOLTS` | `withFeedForward(arbFf + kV_device × rps)`. No velocity field exists on any of the three requests; `FeedForward` exists on all three. |
| REV (`SparkMotorIO`) | `DEVICE_FEEDFORWARD_VOLTS` | `setSetpoint(rot, type, slot, arbFf + kV_device × rps, ArbFFUnits.kVoltage)` — the five-argument overload, **verified** and recorded closed in `DESIGN.md` §5.6. REVLib has no velocity-reference analogue. |
| Phoenix / REV, `RIO_FULL` | `RIO_VOLTAGE_TRIM` | Folded into the SI profile's velocity before the feedforward call, so `Controllers.Feedforward` sees it as part of the commanded velocity — the most natural of the four. |
| Generic (PWM) | `RIO_VOLTAGE_TRIM` | Same as `RIO_FULL`; `GenericMotorIO` forces that location anyway. |

**Identical formula everywhere.** `kV_device = Gains.kV × MechanismUnits.siPerOutputRotation()`, computed once and re-derived on `applyGains()`. Whichever carrier is in use, the *volts* that reach the motor for a given ω are the same to within the device's own kV application — which is precisely what makes `FieldLockedTurretParityTest` a real test rather than a tautology: it runs the same turret config on `TalonFXMotorIO`, `SparkMotorIO`, `GenericMotorIO` and `SimMotorIO`, spins the simulated chassis at a fixed ω, and asserts all four hold the same field-frame heading to within the position tolerance.

**It is never silently dropped on any backend.** That is the whole reason the parameter exists, and `describe()` prints the carrier permanently so the difference between "the turret tracks" and "the turret tracks *this* way" is visible without reading the library's source.

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

**And, new in revision 4, `VELOCITY` defaults to 50 Hz here rather than the 100 Hz a `POSITION` mechanism gets.** The 100 Hz rationale is anti-aliasing against the 50 Hz control loop, which matters when a fast position loop is closing on the signal; a flywheel's `atGoal` is a debounced two-sided predicate over tens of milliseconds and gains nothing from it. The saving is real once §3.5.1's arithmetic is right: `50 + 50 + 50 + 20 + 4 = 174 frames/s` = **2.4 %** of a 1 Mbps bus, against 4.5 % for a position mechanism. `MotorSpec.talonFX(id, bus).signalRateHz(100.0)` restores it for a team that wants a tight velocity loop and has measured the bus headroom for it.

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
   *  9143-B's KitBot got it right with startEnd(...) and Rootstock makes that the only shape. */
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

> **SUPERSEDED ARCHITECTURE REMOVED (revision 4).** Revision 3 opened this section with a
> `Mechanism.simulationPeriodic()` body calling `m_io.simulatedMotorVoltage()` and
> `m_io.updateSimulatedSensors(...)`. **Binding decision D18 deleted all three**, and the two
> `MotorIO` methods never appeared on the §3.3 interface in any revision — so that snippet could
> not have compiled against this document's own seam. It has been replaced, not annotated, because
> leaving it in place with a note would still hand an implementer a call to a nonexistent method.

**The D18 seam, in three parts.** CORE *declares*; Sim *owns*; the vendor adapter *bridges*.

```java
// 1. CORE DECLARES. Pure data, computed once at construction from PositionConfig + Axis,
//    handed to the MechanismGeometrySink of org.rootstock.core.spi at registration.
//    No plant is constructed here and no vendor type is named.
public record MechanismGeometry(
    String name,
    Kind kind,                       // LINEAR | ROTARY | FLYWHEEL | SIMPLE
    DCMotor gearbox,                 // MotorModel x motorCount
    double rotorPerOutput,           // Reduction
    double siPerOutputRotation,      // Axis -- meters or radians per output rotation
    double effectiveRadiusMeters,    // LinearAxis: pitchRadius x stages. NaN for rotary.
    double massKg,                   // LinearAxis carriage mass. NaN for rotary.
    double momentOfInertiaKgM2,      // RotaryAxis / flywheel. NaN for linear.
    double armLengthMeters,          // RotaryAxis. NaN otherwise.
    double siMin, double siMax,      // soft limits, SI
    double siStart,
    boolean simulateGravity) implements StructSerializable { }
```

```java
// 2. THE BACKEND BRIDGES. MotorIO.simHandle() (§3.3) returns the vendor's sim state, wrapped.
//    The CTRE import lives in rootstock-phoenix6 and never leaves it.
public interface SimMotorHandle {                       // org.rootstock.core.spi
  /** Volts the device is currently commanding, INCLUDING the on-device closed loop and its
   *  gravity feedforward. This is what makes vendor simulation exercise the REAL config path. */
  double appliedVolts(double busVoltage);
  /** Write the simulated ROTOR state back to the device's sim state. Rotor, not output --
   *  the conversion is the device's own SensorToMechanismRatio / conversion factor, which is
   *  exactly the code we want under test. */
  void setRotorPosition(double rotorRotations, double rotorRps);
  /** Current draw the plant should charge the battery simulation for. */
  double statorAmps();
}
```

```java
// 3. SIM OWNS AND STEPS. RootstockSim is a LifecycleHook (D26 -- IN-JAR, so it is on
//    RootstockLifecycle.create()'s EXPLICIT list, not ServiceLoader-discovered).
//    This is Simulation's code, shown here only so the seam is legible from CORE's side.
@Override public void simulationTick(double dt) {
  for (SimulatedMechanism m : m_mechanisms) {        // one per declared MechanismGeometry
    m.plant.setInputVoltage(m.handle.appliedVolts(RoboRioSim.getVInVoltage()));
    m.plant.update(dt);                              // dt from Clock, never a literal 0.02
    m.handle.setRotorPosition(m.plant.outputRotations() * m.geometry.rotorPerOutput(),
                              m.plant.outputRps()    * m.geometry.rotorPerOutput());
  }
  BatterySim.calculateDefaultBatteryLoadedVoltage(statorAmpsOfEveryMechanism());
}
```

**What CORE no longer does:** construct a plant, own a `simulationPeriodic()`, or hold a `m_plant` field. **What CORE still does:** decide, from the `Axis` and `SimConfig`, *what plant the mechanism is* — which is the only part a team's declaration can determine and the only part that belongs above the seam. A `MotorIO` returning `Optional.empty()` from `simHandle()` gets a named boot warning from `RootstockSim` (*"Roller: this backend has no simulation; the mechanism will not move in simulateJava"*) rather than silently doing nothing, which is the `0000-XXXX-Robot-Template` NEO gap made visible.

The plant is chosen from the `Axis` and built from `SimConfig` — the team writes neither:

| Axis | Plant | Built from |
|---|---|---|
| `LinearAxis` | `ElevatorSim` | `DCMotor` (from `MotorSpec` model × count), `reduction.rotorPerOutput()`, `sim.carriageMass()`, effective drum radius incl. cascade stages, soft limits, `sim.startingPosition()`, `simulateGravity` |
| `RotaryAxis` with gravity | `SingleJointedArmSim` | same, plus `sim.armLength()` and `sim.momentOfInertia()` (or `SimConfig.estimateArmMoi`) |
| `RotaryAxis` no gravity | `DCMotorSim` | `LinearSystemId.createDCMotorSystem(gearbox, moi, gearing)` |
| `VelocityConfig` | `FlywheelSim` | `LinearSystemId.createFlywheelSystem(gearbox, moi, gearing)` |
| `SimpleConfig` | `DCMotorSim` | as above |

The cascade trick from `9143-2025-A-Updated/.../Elevator.java` (model a 2-stage cascade as an *effective* drum radius so `ElevatorSim` sees carriage motion) is applied by `LinearAxis` automatically: `effectiveRadius = kinematicRadius × stages` — `0.0222339 × 2 = 0.0444679 m` for the §5.4 elevator, the same number the kG derivation uses. **That shared number is the point:** revision 3's kG derivation dropped the `× stages` while the sim kept it, so the simulated elevator and the derived gravity feedforward disagreed by 2× and the table showed no sign of it. One field, one derivation, one place to be wrong.

Crucially, the Phoenix and REV backends simulate through their **own** vendor sim state (reached via `simHandle()`), so the simulation exercises the real `SensorToMechanismRatio` / conversion-factor path. A unit-conversion mistake — the REV `× 60` of §3.7, for instance — shows up in `simulateJava` rather than on the field. This is why `SimMotorIO` is *not* how the vendor backends simulate.

---


## 7. Brief item 4 — Subsystem base and command factories

### 7.1 How a subsystem is declared

There is no `RootstockSubsystem` for a team to extend. A mechanism **is** the subsystem:

```java
public class RobotContainer {
  private final PositionMechanism m_elevator = new PositionMechanism(RobotConfig.ELEVATOR);
  private final PositionMechanism m_arm      = new PositionMechanism(RobotConfig.ARM);
  private final SimpleMechanism   m_intake   = new SimpleMechanism(RobotConfig.INTAKE);

  public RobotContainer() {
    RootstockRegistry.addAll(m_elevator, m_arm, m_intake);   // ONE list, see below
  }
}
```

*(D12: revision 3 wrote `Rootstock.registry().addAll(...)`. The `Rootstock` god-object is deleted; `org.rootstock.core.RootstockRegistry` is the replacement and `addAll` is static on it. D27 makes this the **only** registration call — `SelfTest.registerAll`, `TuningRegistry.registerAll` and `HealthMonitor.watch` are not public API.)*

`RootstockRegistry.addAll` replaces the seven-place edit that `0000-XXXX-Robot-Template/README.md:438-447` documents as the workflow for adding a mechanism. Registered mechanisms automatically get:

* **collected config validation, the global CAN-ID scan, the setpoint-name check, and SAFE_MODE entry** (§5.6) — this is the only place any of that happens
* `periodic()` fan-out (or CommandScheduler registration, if `registerWithScheduler()` was called)
* **`MechanismGeometry` declaration into the `MechanismGeometrySink`** so `RootstockSim` can build and step the plant (D18, §6.7). *(Revision 3 said "`simulationPeriodic()` fan-out" here; D18 deleted that method. The registry declares geometry; it does not fan out a sim tick, because CORE does not own one.)*
* stop-on-disable (replacing the hand-maintained `disabledInit()` lists in `9143-*/RobotContainer.java:369-374` and `0000-XXXX/RobotContainer.java:450-459`)
* a **verified** full-config re-apply on `disabledInit()` — the safe place for the blocking path
* telemetry publication under `/Rootstock/<name>/...`
* alert registration
* tunable registration (the explicit allowlist of §1.2)
* config snapshot logging
* inclusion in `SystemCheck`
* inclusion in the `describe()` boot dump

Adding a mechanism is now: write a config, construct it, add it to the registry. Three lines, one file.

**Backend selection is one call, not a nested ternary.** `0000-XXXX-Robot-Template/RobotContainer.java:154-171` has nine copies of a 3-way nested ternary. `MotorIOFactory` owns it once:

```java
package org.rootstock.hardware;

import org.rootstock.core.spi.RobotMode;   // core.spi, NOT telemetry -- rule 9, the same move
                                            // that relocated Tier and LogConfig on 2026-08-08.
                                            // Telemetry still owns what the three modes MEAN.

public final class MotorIOFactory {
  /** REAL/SIM/REPLAY x backend, decided in ONE place from the MotorSpec sealed hierarchy.
   *  REPLAY is UNCONDITIONAL as of revision 3: AdvantageKit is a required dependency, so
   *  RobotMode.REPLAY always exists and always works. Revision 2 needed a runtime refusal
   *  path for "the installed backend cannot replay"; that path is deleted with its cause. */
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
      // D10: MatchImpact is required at every call site -- a button that silently does nothing
      // is exactly the kind of thing that must reach the driver mirror. D12: Alerts, not
      // Rootstock.alerts(). The handle is created once per mechanism in the constructor; only
      // the text varies, so nothing allocates on the command-construction path either.
      .beforeStarting(() -> m_unknownSetpointAlert.text(unknownSetpointMessage(s)).set(true))
      .withName(m_name + ".goTo(" + s.name() + ")[REFUSED]");
}

// In the constructor:
//   m_unknownSetpointAlert =
//       Alerts.error(m_name, m_name + "/unknown-setpoint", MatchImpact.BLOCKS_MATCH);
```

Three rules the library enforces:

1. **Every factory returns a fresh instance.** WPILib forbids reusing a composed command; `0000-XXXX-Robot-Template/RobotContainer.java:322-443` works around this by storing auto *names* and rebuilding. Rootstock never hands out a cached `Command`.
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

The routine's ramp rate, step voltage, and timeout default from `PositionLimits` and are overridable. SysId **refuses to run** in SAFE_MODE, while disabled, when the mechanism is unhomed, or when `MatchContext.isFMSAttached()`. It aborts on a soft-limit approach, a stall, a device reset, or a follower disagreement — a characterization routine that drives a geared arm into a hard stop at step voltage is a broken gearbox.

In `OutputMode.TORQUE_CURRENT` (post-v0.1), Rootstock emits the amps-not-volts caveat that `reefscape2025/.../Elevator.java:180` handles with the comment "Gaslight SysId since motor is actually running amps instead of volts" — the library states it in `describe()` and in the log rather than requiring the team to know.

Four SysId wrapper methods copy-pasted into three subsystems in `reefscape2025` become zero lines.

---

## 8. Brief item 5 — Superstructure and state machine

### 8.1 Shape

The strongest patterns in the survey are 4738's *orthogonal* sub-states (an arm state + a climb state + a claw state composed into a `SuperState`, which is what keeps 50 states tractable), 9143-A's *deferred planning from live state* with measured-state gates instead of timeouts, and `0000-XXXX`'s *on-entry latch*. Rootstock composes all three and fixes the failure mode all three share.

```java
package org.rootstock.superstructure;

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
public final class Superstructure<S extends Enum<S> & SuperState> implements TelemetrySource {

  public Superstructure(Class<S> stateType, S idleState, SafetyModel safety,
                        List<Interlock<S>> interlocks, Mechanism... mechanisms);

  // ---- TelemetrySource, the declaration half (§1.1b, `design/04` §1.1) -------------------
  /** The superstructure publishes Blocked and Plan under its OWN name (§8.2), so it registers
   *  and declares like any other source rather than riding on a mechanism's block. Returns
   *  the literal "Superstructure", which is the segment `design/04` hard-codes; §1.1b states
   *  the one-per-robot limitation that follows. */
  @Override public String telemetryName();
  /** Called once from RootstockRegistry.addAll (D27). Declares the state enum and the two
   *  extra(...) keys. Body in §1.1b. */
  @Override public void describe(TelemetryDescriptor d);

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
      // STANDARD tier, String. Superstructure keys are extra(...) declarations on the
      // superstructure's own TelemetryDescriptor; `design/04` §2.3 shape: log(String, String).
      RootstockLog.log(kBlocked, blocked.get().describe());
      m_blockedAlert.set(true);
      return;                              // hold the current state; do NOT half-execute
    }
    m_blockedAlert.set(false);
    m_plan = m_planner.plan(measuredConfiguration(), m_requested);
    RootstockLog.log(kPlan, m_plan.waypointNames());   // String[], STANDARD; §2.3 log(String, String[])
  }

  // Advance on MEASURED arrival (or early-release), never on a timer.
  if (m_plan.currentWaypointSatisfied(this)) m_plan.advance();
  m_active = m_plan.currentState();

  boolean onEntry = m_active != m_previousActive;
  m_previousActive = m_active;

  applyGoals(m_active, onEntry);
}
```

**Why measured-state gates, never timeouts:** `9143-2025-A-Updated/Superstructure.java` uses `Commands.waitUntil(() -> elevator.getCurrentPosition() >= handoff)` throughout, and its ascending escape re-evaluates its ceiling every loop so the elevator target ratchets up as the arm swings — avoiding stop-and-go stutter. A time-based version of the same sequence is both slower and unsafe when the mechanism is loaded or cold. Rootstock has **no** time-based waypoint gate. A waypoint may carry a *timeout*, but a timeout only raises `<name>/transition-timed-out` and holds; it never advances.

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

A blocked request is **visible, not silent**: the request is retained (so releasing the interlock completes the move), an alert names the interlock and its `explanation`, and `/Rootstock/Superstructure/Blocked` carries the string.

Interlocks are also the documented answer for constraints that `SafetyModel`'s two-axis rectangles cannot express — see §13 OQ #7.

### 8.4 Collision avoidance: declarative forbidden zones

Robot-specific geometry, generic machinery. The team declares *forbidden regions of the two-axis configuration space*, not a branch tree.

```java
package org.rootstock.superstructure;

/**
 * Collision avoidance over a 2-axis configuration space (typically elevator height x arm angle).
 * Zones are axis-aligned rectangles in USER UNITS. The planner routes around them.
 * Replaces the ~250-line hand-written branch tree in
 * 9143-2025-A-Updated/src/main/java/frc/robot/Superstructure.java
 * (planMove / escapeCurrentPose / travelAndFinish / avoidClimb / transitionWrist).
 */
public final class SafetyModel {
  // Mechanism, not PositionMechanism: the model needs exactly two things from each axis, its name
  // and its measured value in user units, and the base type already supplies both.
  public static Builder over(Mechanism axisA, Mechanism axisB);

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

This goes in `RobotContainer`, next to the mechanisms, and not in `RobotConfig`: `over(...)` takes
the two constructed `PositionMechanism`s, because the model reads their **measured** positions every
loop. Handing it the two `PositionConfig` records instead does not compile. §9.3 shows it in place.

```java
// in RobotContainer, after ELEVATOR and ARM have been constructed
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
[Rootstock][ERROR] Superstructure: no safe route from (Elevator 6.2 in, Arm 12.0 deg) to
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
[Rootstock][WARN] Superstructure: synchronizedAxes(false) is set on the (Elevator, Arm) pair.
  Collision avoidance is now tested against the straight line between configurations, but the
  two axes run independent profiles and the real path is L-shaped. A forbidden zone that the
  diagonal misses can still be entered. This alert stays up until synchronizedAxes(true).
```

The planner also re-evaluates a moving ceiling each loop for the ascending case, exactly as 9143-A does, so a route does not stutter: waypoint targets for the leading axis are recomputed from the trailing axis's live position rather than pinned at plan time.

### 8.5 The default-output inversion (the bug class this kills)

`0000-XXXX-Robot-Template/Superstructure.java:130-246` requires every `case` to remember to reset every actuator. The comments prove the cost: *"Don't leave rollers running at whatever the previous state set"* (MANUAL), *"entering from AIM/SHOOT must spin the flywheels down"* (EJECT), and an `AIM` case that silently forgets `intake.retract()`.

Rootstock inverts it:

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

`/Rootstock/Superstructure/{Requested, Active, Previous, Transitioning, AtState, Plan[], Blocked, BlockedReason, SafeZoneViolation, TimeInStateSec, PlannedSeconds, PlannedSource, Synchronized}`.

`Plan[]` in particular turns "why is the arm moving there first?" from a code-reading exercise into a dashboard glance — which is the difference between a CSA helping you and a CSA giving up. All keys are precomputed strings, as in §6.1.

### 8.8 Static analysis and measured transition costs

Revision 1 omitted the specific mechanisms the dossier ranks as the **#1 elite differentiator**, and nowhere stated the resulting ceiling. 254 measures transition costs on the real robot and persists them (`transition_costs.txt`, `buildCharacterizationCommand()`), auto-generates edges from `allowedNextStates()`, uses gateway states with restricted exits, and precomputes all-pairs routes; 6328 runs BFS over an explicit `DefaultDirectedGraph`. Rootstock substituted a greedy corner-escape heuristic capped at four waypoints with **no cost model at all**, so `AutoStep.budget()` and `plannedTransitionSeconds()` were guesses.

Two members close the gap that can be closed, and §13 states the part that cannot.

**(a) `report()` — pure math, no HAL, called automatically at construction and printed in the boot dump.** 0.3 person-weeks.

```java
package org.rootstock.superstructure;

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

**(b) `characterizeTransitions()` — measured costs. Built at M14 (with the `SafetyModel` router), 0.5 person-weeks, and heavily safety-gated.** *(Revision 3: revision 2 said "v0.2"; there is no v0.2. It is in v0.1, at M14 — and it is the first thing depth lever L7 drops if capacity forces a reduction.)*

```java
/**
 * Drives every declared state pair once, times it, and writes
 * /home/lvuser/rootstock/transition_costs.json. Read back at boot by
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
8. **The written file records provenance**: robot serial, Rootstock version, config hash, date, and the constraint scale used. A `transition_costs.json` whose config hash does not match the running config is **ignored** with a warning, never silently trusted — a re-geared mechanism must be re-characterized.

`plannedTransitionSource()` reports `MEASURED` or `PROFILE_BOUND` so an auto routine's timing budget is never a silent guess.

### 8.9 Setpoint names cannot fail at button-press time

`AxisGoal.named("L4")`, `elevator.goTo("L4")` and `.setpoint("L2", ...)` form a string-keyed namespace. Revision 1 specified no validation anywhere, so a typo — `"L4 "`, `"l4"`, `"SCORE"` on a mechanism that declares `"Score"` — produced a failure at the moment a button was pressed, which for `SuperState.L4` is mid-match, and no document said whether that failure was an exception (kills the command, possibly the scheduler), a silent no-op (worst case: the elevator simply does not move and nobody knows why), or an alert. That is the 11-p.m.-before-a-competition failure, on the single most-typed identifier in the API.

Three layers, in order of preference:

**1. The typed form is the documented default.** `PositionConfig.setpoint(String)` returns a `Setpoint` handle a team holds as a `public static final` (§5.4), so `AxisGoal.of(RobotConfig.ELEVATOR_L4)` is checked by the compiler. Every example in this document and in the README uses it. The string form is the escape hatch, not the norm.

**2. Every string is validated at construction, with a suggestion.** `Superstructure.Builder.build()` and `RootstockRegistry.addAll(...)` resolve every `AxisGoal.named(s)` in every `SuperState`, and every unresolved `Setpoint`, against the declaring mechanism's `List<Setpoint>`, and collect one FATAL `ConfigError` per miss (all of them, at once, into SAFE_MODE):

```
org.rootstock.config.ConfigError [FATAL]: Rootstock unresolved setpoint

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
[Rootstock][ERROR] Elevator: goTo("SCORE") -- no such setpoint. Elevator declares:
  STOW, L1, L2, L3, L4. The command did nothing and the mechanism is holding position.
  (Did you mean a setpoint on Arm? Arm declares: STOW, INTAKE, SCORE.)
```

Note the last line: the checker searches *sibling* mechanisms too, because "I put the arm's setpoint name on the elevator" is the most common form of this mistake.

---


## 9. End-to-end: a complete two-mechanism scoring robot

Everything a team writes. Elevator + arm + roller, collision avoidance, driver bindings, autonomous hooks, simulation, live tuning. No IO files, no sim code, no telemetry code, no alert code, no unit conversions.

> **PROVENANCE, STATED HONESTLY (revision 4). Read this before trusting a line below.**
>
> Revision 3 said, in the present tense: *"Every snippet in this section **is** extracted from a
> compiled, executed test (`ExampleRobotCompilesTest`, `ExampleRobotSimTest`,
> `DescribeSnapshotTest`, `AllocationTest`)."* **That sentence was false**, in two ways that
> compound:
>
> 1. **No code exists.** Rootstock is ~23,000 lines of design documents and zero lines of Java.
>    None of those four test classes exists, so nothing was extracted from anything. `README.md`
>    line 5 says this plainly — *"Every code block in these documents is a specification, not a
>    snippet you can run"* — and this section contradicted it.
> 2. **The snippets it covered provably could not have compiled.** §9.1 carried the uncorrected
>    `34.126` gear ratio `[INTENTIONAL-34.126]` (a 1.92× error, and the *reason* `Validation`'s D2b identity rule exists);
>    §9.3 called `Rootstock.registry()`, a facade **D12 deleted**; and the request chains those
>    configs drive called `MotionMagicVoltage.withVelocity(...)`, which does not exist. A claim of
>    machine-checked provenance is worth less than nothing when the thing it vouches for is wrong,
>    because it stops the next reader from checking.
>
> **The rule, restated in the tense it deserves.** Every snippet in this section is a
> **docs-as-tests target**. The four extraction tests are **built at M24** (`ROADMAP.md` §5), they
> are **release-blocking under principle 12**, and until they exist the guarantee is that a human
> re-derives these blocks at every revision — which is how revision 4 found the three defects
> above, and is not a substitute for CI.
>
> **What each test will assert, so M24 has a specification rather than an aspiration:**
>
> | Test | Asserts |
> |---|---|
> | `ExampleRobotCompilesTest` | §9.1–§9.4 are extracted verbatim from `src/test/fixtures/example-robot/` and compiled against the published `rootstock` jar. A drifted snippet fails the build. |
> | `ExampleRobotSimTest` | That fixture boots headless, homes the elevator, runs `getAutonomousCommand()` to completion, and ends in `SuperState.IDLE` with both axes at goal. |
> | `DescribeSnapshotTest` | `describe()` on `RobotConfig.ELEVATOR` and `RobotConfig.ARM` string-matches §4.4 and §3.5.7 **verbatim** — including `0.279400 m`, `5.0000 drum rot`, `kG 0.33`, `65.411:1` and `kG 0.29`. This is the test that would have caught both the dropped cascade and the 34.126 `[INTENTIONAL-34.126]`. |
> | `AllocationTest` | One loop of that same three-mechanism fixture allocates zero bytes after warmup (§11 item 18, an **M5** gate condition). |
> | *(added rev 4)* `VendorRequestChainsCompileTest` | **Every** vendor request-builder chain in §3.5.6 and §3.7 compiles against the real Phoenix 6 / REVLib jars. This is the test whose absence let `MotionMagicVoltage.withVelocity(...)` ship in a document that promised every API name was read from a javadoc. |
>
> **Until those tests exist, here is the compiling copy.** `rootstock/src/test/java/org/rootstock/example/` holds this same robot -- `RobotConfig.java`, `RobotContainer.java`, `ScoringState.java` -- written against the shipped API and compiled by the build. §9.1 to §9.4 below were extracted from this document, compiled against the `rootstock` and `rootstock-phoenix6` classes with `javac`, and edited until the compiler was silent; they are not machine-checked on every commit yet, so if a block below and that source set disagree, **the source set is right.** That example uses simulated motor backends, because the test source set may not depend on a vendor artifact; its `RobotConfig` javadoc names the lines that changes and why.

### 9.1 `RobotConfig.java` — 3 mechanism configs + safety + typed setpoints (one file)

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;
import org.rootstock.config.*;          // PositionConfig, MotorSpec, HomingStrategy, Setpoint, ...
import org.rootstock.units.*;           // LinearAxis, RotaryAxis
import org.rootstock.control.Gains;     // Gains is in control, NOT config
import org.rootstock.pure.units.Reduction;   // Reduction is in pure.units, NOT units

public final class RobotConfig {

  public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
      // .foc(true) = FOC on the voltage requests. Gains stay VOLTS-per-SI.
      .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                        .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
      .reduction(Reduction.ofStages(3.0, 4.0))                  // 12:1 -> 2.251 m/s free
      .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))        // 22 x 0.25 in x 2 = 0.279400 m/rot
      .feedback(new FeedbackSpec.RotorOnly())
      .softLimits(Inches.of(0.0), Inches.of(55.0))
      .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
      // ControlLocation omitted -> defaults to ON_MOTOR_PROFILED (TalonFX leader), and
      // describe() prints that with its provenance.
      // Gains: kP V/m, kD V/(m/s), kS V, kV V/(m/s), kA V/(m/s^2), kG V.
      .gains(Gains.realOrSim(
          Gains.pid(80.0, 0, 2.0).withKs(0.22).withKv(5.00).withKa(0.06).withKg(0.33),
          Gains.pid(150.0, 0, 0).withKv(5.00).withKa(0.06).withKg(0.33)))
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
      // ofTeeth(drivenTeeth, drivingTeeth): 58/10 x 58/18 x 42/12 = 65.411:1  (section 4.2)
      .reduction(Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12))   // 65.411:1
      .axis(RotaryAxis.arm(Degrees.of(0.0)))    // |horizontalAt| <= 90 deg, Phoenix requirement
      // D2b identity: rotorPerSensor x sensorPerOutput == 65.411 x 1.0 == reduction  OK
      .feedback(new FeedbackSpec.FusedCancoder(23, "rio", Rotations.of(-0.1387), 65.411, 1.0))
      .softLimits(Degrees.of(-15.0), Degrees.of(105.0))
      .currentLimits(CurrentLimits.of(Amps.of(60), Amps.of(35)))
      // Gains: kP V/rad, kD V/(rad/s), kV V/(rad/s), kA V/(rad/s^2), kS and kG volts.
      // Derived at 65.411:1 in section 5.5. kG/kA scale as 1/G, kV as G.
      .gains(Gains.realOrSim(
          Gains.pid(5.0, 0, 0.18).withKs(0.20).withKv(1.25).withKa(0.010).withKg(0.29),
          Gains.pid(10.0, 0, 0).withKv(1.25).withKa(0.010).withKg(0.29)))
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

  // The collision model is NOT here. SafetyModel.over() reads the two axes' measured positions
  // every loop, so it takes the constructed mechanisms, not these config records; section 9.3
  // builds it. This file holds numbers.

  private RobotConfig() {}
}
```

### 9.2 `SuperState.java` — the state machine (one file)

```java
package frc.robot;

import java.util.Map;
import org.rootstock.config.Setpoint;
import org.rootstock.mechanism.Mechanism;
import org.rootstock.superstructure.AxisGoal;

// The three mechanisms, one import each. RobotConfig declares PositionConfigs under the SAME
// three names, so `import static frc.robot.RobotContainer.*` next to a RobotConfig wildcard
// would make every reference below ambiguous. Naming the members resolves it: a single static
// import wins over an on-demand one.
import static frc.robot.RobotContainer.ELEVATOR;
import static frc.robot.RobotContainer.ARM;
import static frc.robot.RobotContainer.ROLLER;
import static frc.robot.RobotConfig.*;      // typed Setpoint handles

public enum SuperState implements org.rootstock.superstructure.SuperState {

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

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;              // REQUIRED -- see getAutonomousCommand
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.rootstock.core.RootstockRegistry;            // D12 -- there is no `Rootstock` class
import org.rootstock.core.hid.Rumble;
import org.rootstock.core.hid.RumblePattern;
import org.rootstock.hardware.phoenix.TalonFXMotorIO;   // rootstock-phoenix6 artifact
import org.rootstock.mechanism.*;                       // PositionMechanism, SimpleMechanism
import org.rootstock.superstructure.AxisGoal;
import org.rootstock.superstructure.Interlock;
import org.rootstock.superstructure.SafetyModel;
import org.rootstock.superstructure.Superstructure;
import org.rootstock.units.Range;

public class RobotContainer {

  public static final PositionMechanism ELEVATOR = new PositionMechanism(RobotConfig.ELEVATOR);
  public static final PositionMechanism ARM      = new PositionMechanism(RobotConfig.ARM);
  public static final SimpleMechanism   ROLLER   = new SimpleMechanism(RobotConfig.ROLLER);

  // The collision model lives HERE, not in RobotConfig, because SafetyModel.over() reads the two
  // axes' MEASURED positions every loop: it takes the mechanisms, not the config records.
  public static final SafetyModel SAFETY = SafetyModel.over(ELEVATOR, ARM)
      .forbid("arm-through-chassis",
              Range.of(Inches.of(0), Inches.of(9)), Range.of(Degrees.of(-15), Degrees.of(40)),
              "the arm hits the chassis crossbar below 9 in")
      .corridor("travel-tucked", Range.of(Inches.of(0), Inches.of(55)), 95.0)
      .synchronizedAxes(true)          // default; stated here so the choice is visible
      .build();

  private final Superstructure<SuperState> m_super =
      new Superstructure.Builder<>(SuperState.class, SuperState.IDLE)
          .safety(SAFETY)
          // defaultFor registers the mechanism as well as declaring what it does when no state
          // names it, so there is no separate "here are my mechanisms" list to keep in sync.
          .defaultFor(ELEVATOR, AxisGoal.of(RobotConfig.ELEVATOR_STOW))
          .defaultFor(ARM,      AxisGoal.of(RobotConfig.ARM_STOW))
          .defaultFor(ROLLER,   AxisGoal.percent(0.0))
          // interlock() takes the Interlock record, not five loose arguments: the record is the
          // thing SuperstructureReport lists and the dashboard names, so it has to exist anyway.
          .interlock(new Interlock<>(
                     "no-score-until-homed",
                     from -> true, to -> to != SuperState.IDLE,
                     () -> ELEVATOR.isHomed() && ARM.isHomed(),
                     "the elevator has not homed yet; press Start to home"))
          .build();     // validates every setpoint reference and runs report() -- sections 8.8, 8.9

  private final CommandXboxController m_driver = new CommandXboxController(0);

  public RobotContainer() {
    // The ONE place validation, the CAN-ID scan, setpoint-name resolution, and SAFE_MODE live.
    RootstockRegistry.addAll(ELEVATOR, ARM, ROLLER, m_super);      // D12 / D27

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
    ROLLER.holding().onTrue(
        Rumble.on(m_driver.getHID())
            .side(GenericHID.RumbleType.kBothRumble)
            .play(RumblePattern.pulse(0.4, Seconds.of(0.25))));

    // Escape hatch, on page 1: anything Rootstock does not model, do on the real device.
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
        // and it is a NAMED CASE in ExampleRobotCompilesTest's charter for M24 -- a direct
        // counter-example proving that "compiles" is not the property the docs-as-tests rule
        // buys, which is why ExampleRobotSimTest asserts the auto routine's END STATE and not
        // merely that it built.
        .andThen(Commands.waitUntil(m_super.at(SuperState.L4).and(ELEVATOR.atGoalTrigger())))
        .andThen(m_super.request(SuperState.EJECT).withTimeout(0.5))
        .andThen(m_super.request(SuperState.IDLE));
  }
}
```

### 9.4 `Robot.java` — two shapes, and the second one is not a downgrade

**The convenience shape.** One base class, and it is the only one Rootstock ships (§1.1a):

```java
package frc.robot;

import org.rootstock.core.RootstockRobot;
import org.rootstock.core.spi.LogConfig;   // core.spi, NOT telemetry (D31, ArchUnit rule 9)

public class Robot extends RootstockRobot {        // extends LoggedRobot underneath
  private final RobotContainer m_container;

  public Robot() {
    // D13a: the argument is an IMMUTABLE LogConfig value, not a Consumer. super() alone is
    // legal and means super(LogConfig.defaults()); it is spelled out here because
    // "who starts the Logger" must be a visible decision (D29).
    super(LogConfig.defaults().withWpilogFolder("/U/logs"));
    m_container = new RobotContainer();          // registers via RootstockRegistry.addAll
    lifecycle().init();                          // last; idempotent, so it is safe to omit
  }
  // Nothing else. There is no simulationPeriodic() to write or to leave empty: D18 deleted
  // Mechanism.simulationPeriodic(), and RootstockSim steps every declared plant from its own
  // LifecycleHook. Revision 3's version of this block overrode it with a "nothing: the registry
  // handles it" comment, which described a fan-out that no longer exists.
  //
  // And there is no robotInit(): D13a deleted RootstockRobot's override of the WPILib hook, and
  // D29 renamed the lifecycle method to init(). Revision 5 of this document showed
  // `@Override public void robotInit()` in the block below; it is gone from both shapes.
}
```

`RootstockRobot.robotPeriodic()` already runs `beforeUserPeriodic()` → `CommandScheduler.run()` → `afterUserPeriodic()`, and `RootstockRobot.disabledInit()` already calls `RootstockLifecycle.disabledInit()`, so neither is written here.

**The partial-adoption shape**, for an existing repo that will not change its base class — which is how 8793 and 9143 consume M1:

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.littletonrobotics.junction.LoggedRobot;
import org.rootstock.core.RootstockLifecycle;
import org.rootstock.core.spi.LogConfig;   // core.spi, NOT telemetry (D31, ArchUnit rule 9)

public class Robot extends LoggedRobot {         // the team's own, already there
  private final RootstockLifecycle m_rootstock;
  private final RobotContainer m_container;

  public Robot() {
    // adoptExistingLogger(), NOT defaults(): this repo already calls Logger.start().
    // Starting it twice is a crash, and the library refuses to guess which one you meant (D29).
    m_rootstock   = RootstockLifecycle.create(LogConfig.adoptExistingLogger());
    m_container = new RobotContainer();
    m_rootstock.init();                            // last: after everything is registered
  }

  @Override public void robotPeriodic() {
    m_rootstock.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_rootstock.afterUserPeriodic();
  }
  @Override public void disabledInit()       { m_rootstock.disabledInit(); }
  // No simulationPeriodic(): D18. RootstockSim is a LifecycleHook and runs inside
  // beforeUserPeriodic()/afterUserPeriodic() like everything else.
  // No robotInit() either -- see the note in the convenience shape above.
}
```

Revision 2's version of this block said *"or `LoggedRobot`; Rootstock does not care"*, and starting from a plain `TimedRobot`. **That is no longer true and the line is removed.** Under maintainer decision 3 both shapes extend `LoggedRobot`: `RootstockRobot` does it for you, `RootstockLifecycle` assumes you already did. A team on plain `TimedRobot` with no logger must adopt AdvantageKit first. `RootstockLifecycle` keeps the *seam* optional; it cannot keep the *dependency* optional, and §1.1a says so rather than pretending.

`RootstockLifecycle.disabledInit()` — reached either way — is where the **verified**, blocking, full-config re-apply happens (§3.9) — the robot is disabled, the loop budget is irrelevant, and it guarantees that anything a mid-match device reset, a live-tuning `applyFast` frame loss, or an unconfirmed homing restore (§6.3) left inconsistent is restored before the next enable. It calls `RootstockRegistry.onDisable()` internally (D12), so a team driving the lifecycle by hand does not have to remember both.

### 9.5 What the team did **not** write

No `ElevatorIO` / `ElevatorIOTalonFX` / `ElevatorIONeo` / `ElevatorIOSim`; no `ArmIO`×4; no `RollerIO`×4 (12 files). No `TalonFXConfiguration` blocks. No `StatusSignal` caching or `optimizeBusUtilization`. No unit conversions. No SI conversions. No soft-limit derivation. No `simulationPeriodic()` — there is no such method to write (D18). No `ElevatorSim` / `SingleJointedArmSim` construction. No `SmartDashboard`/`Logger.recordOutput` calls. No `Alert` declarations. No tunable plumbing. No SysId wrappers. No `disabledInit()` stop list. No collision-avoidance branch tree. No manual-control deadband code. No homing routine. No `atGoal` implementation. No `hasResetOccurred` handling. No `GravityArmPositionOffset` sign reasoning.

Approximate line count for an equivalent robot in the user's existing style: **~2,400 lines across 22 files**. Above: **~165 lines across 3 files**.

And it runs, with physics, in `./gradlew simulateJava` before the robot exists — including the current-spike homing routine, because `ElevatorSim` produces a real current spike at its travel limit.

### 9.6 What a student changes to tune it

Nothing in code. `/Tuning/Elevator/{kP,kI,kD,kS,kV,kA,kG,maxVelocity,maxAcceleration,jerk,tolerance,velocityTolerance,goalDebounceSeconds,manualDeadband,manualScale}` and `/Tuning/Elevator/Setpoints/{STOW,L2,L3,L4}` are live in AdvantageScope/Elastic whenever `TuningRegistry.isTuningEnabled()` is true (**D12** — revision 3 said `Rootstock.TUNING_MODE`, on a facade that no longer exists) — the explicit allowlist of §1.2, nothing more. `/Tuning/Elevator/reduction` does **not** exist, on purpose.

`jerk` is on that list, and revision 4 makes it real: `MechanismUnits.toOutputRps3` is what converts the slider's user-unit value into the `MotionMagicJerk` the device wants (§3.5.4 step 7). Before that method existed the topic was published, was tunable, and was passed to the device **unconverted**. *(Cross-doc note: `design/02`'s `ProfileConstraints` cannot currently represent `jerk` at all, which is `REVIEW.md` open question 4 for the maintainer. This document keeps it, and lists the requirement under `contractsRequested`.)*

Changes are pushed to the controller on the next loop via `MotorIO.applyGains`, which is the **non-blocking** `applyFast` path (zero-timeout `apply`, no read-back, no retry) and is budgeted at 1.0 ms and traced. **"On the next loop" is the robot side of the round trip, not the whole of it:** per **D11a** the honest end-to-end latency from dragging a slider is **100–150 ms**, because AdvantageScope and Elastic are NT *clients* whose default publish period is 100 ms. With tuning disabled, every one of those reads compiles down to a constant and publishes nothing.

---

## 10. WPILib 2027 migration plan

The 2026 season is over and 2027 is a hard break (`edu.wpi.first.*` → `org.wpilib.*`, Java 25, SystemCore, NT3 removed, Commands v3, no Shuffleboard/SmartDashboard). The offseason build window is now, and everything above is designed so the migration is mechanical.

1. **One line at a time; the dual-compile scaffolding lives and dies inside M12.** `ROADMAP.md` §7.2 is authoritative here, and docs 02 §17 and 04 §13.1 already describe this model — **revision 3 of this document did not, and said the opposite.**

   > **SUPERSEDED (revision 4).** Revision 3 item 1 read: *"Two release lines from day one, one
   > source tree. `rootstock-2026` (`frcYear: "2026"`) and `rootstock-2027`
   > (`wpilibYear: "2027_alphaN"`), built from the same sources with a package-rewrite step."*
   > That describes a **permanent** dual-line, dual-publish model with a package-rewrite pipeline
   > maintained for years. Decision 1's roadmap deleted it, and an implementer following this
   > document would have built and carried infrastructure nobody asked for. `design/02` §17 states
   > it directly — *"There is no long-lived 2027 branch: the generated-source variant and the
   > dual-compile CI exist only inside M12 and are deleted at its end"* — and `design/04` §13.1
   > makes M12 the only date-triggered milestone, with development staying on WPILib 2026.2.2
   > through M11.

   The model, in three phases:

   | Phase | Line | What exists |
   |---|---|---|
   | **M1 → M11** | 2026 only | One source tree, one artifact set, `frcYear: "2026"`, built against WPILib 2026.2.2. No 2027 branch, no package-rewrite step, no dual-compile CI. |
   | **M12** (date-triggered by the first WPILib 2027 alpha) | both, temporarily | The generated-source variant and the dual-compile CI matrix are **created inside M12** as porting scaffolding, used to drive the `edu.wpi.first.*` → `org.wpilib.*` migration to green, and **deleted at M12's end**. 8.0 pw. |
   | **M13 → M24** | 2027 only | One source tree again, `org.wpilib.*`, Java 25, SystemCore. The 2026 line is frozen, not co-developed. |

   What makes this affordable is unchanged and is the real content of this item: **WPILib imports are already narrow** — `Measure`/units, `Rotation2d`, `MathUtil`, `TrapezoidProfile`, the three feedforward classes (reached only through `Controllers.Feedforward`), `PIDController`, the four physics sims, `Alert`, `DriverStation` (reached only through `MatchContext`), `Timer` (reached only through `Clock`), `Subsystem`/`Command`/`Trigger`/`Commands`. The port is mechanical because the surface is small, not because a rewrite pipeline exists.

   **The cost of the M12-only model, stated:** during M12 there is no shippable 2026 artifact under active development, and if the port slips the 2026 line is what teams keep using. `ROADMAP.md` §5.1's solo column has M12 taking ~19 weeks against a ~5–8 week beta window, which `REVIEW.md` M17 flags as unsatisfiable at solo pace; that is a schedule problem owned by `ROADMAP.md`, not a design problem this document can fix, and it is not softened here.
2. **`Mechanism` is already the noun.** Commands v3 uses "mechanism" for the exclusively-owned resource, and our `Mechanism` deliberately implements the `Subsystem` *interface* only. The v3 facade is one adapter class; nothing above it changes.
3. **Commands v2 is the target; v3 is an adapter.** v2 survives into 2027. The coroutine `yield()` footgun is real and disproportionately hurts the target audience. CORE ships v2 first and a `commands3` adapter artifact second.
4. **Almost nothing removed in 2027 is used, and the two exceptions are named rather than glossed.** No Relay/AnalogOutput/SPI/DMA/Counter/Ultrasonic/AnalogTrigger/interrupts/Servo, no NT3, no Shuffleboard/SmartDashboard, no `MutableMeasure`. Two live exposures:
   * **`robotInit()` — was a live exposure, and as of D13a it is not one.** Revision 3 listed it as "not used" (false at the time — `RootstockRobot` overrode it); revision 4 corrected that to "the single override in the library"; **D13a then deleted the override**, and `RootstockLifecycle`'s method is D29's `init()`. So the library's exposure to this 2027-removed hook is now **zero**, the initialisation-order problem that motivated the override is solved by `init()`'s idempotence plus a constructor-tail call site (§1.1a), and ArchUnit rule 5 needs no exception. What remains is a **compatibility note, not a blocker**: whatever `LoggedRobot`'s 2027 line exposes as a post-construction start hook is still **[UNVERIFIED]** (neither WPILib 2027 nor AdvantageKit's 2027 branch exists yet), but nothing in Rootstock depends on the answer. It stays on M12's checklist and in open question 15 at that reduced weight.
   * **`DutyCycleEncoder`** for `FeedbackSpec.DioAbsolute` — **[UNVERIFIED]** whether it survives the Counter removal; if not, `DioAbsolute` becomes 2026-only and the config builder says so.
5. **`Math.clamp` / `MathUtil.clamp`** — CORE uses an internal `RootstockMath.clamp` (Java 17 has no `Math.clamp`, and `MathUtil.clamp` is renamed in 2027). One-line seam, already used in §6.2.
6. **`calculateWithVelocities` is the 2026-and-forward form.** The deprecated `calculate(position, velocity, acceleration, dt)` overloads are not used anywhere, so the 2027 removal is a no-op for us. `Controllers.Feedforward` is the single point of contact.
7. **SystemCore has multiple CAN buses**, which makes `canBus` a first-class field on every `MotorSpec` today rather than a 2027 retrofit. It also makes `m_dynamicCapable`'s bus test (§6.2) forward-compatible.
8. **WPILib 2027 ships first-party `Tunable` and `Telemetry` APIs.** The Tuning and Telemetry domains must be designed to *re-point* at those, not compete. CORE touches them only through `Tunable` and the `RootstockLog` statics, so CORE is insulated from a change of *facade implementation*. **Revision 3 narrows this claim honestly:** it no longer holds for the *inputs* classes. `MotorInputs implements LoggableInputs` names an AdvantageKit type in CORE, so if WPILib's first-party telemetry ever made AdvantageKit unnecessary, CORE would have to change with it. Revision 2's `RootstockInputs` bought exactly that insulation, and maintainer decision 3 spent it. This is one of the two places (with §1.7.6) where a decision-3 cost lands inside CORE rather than in `design/04`.
9. **AdvantageKit must publish for WPILib 2027 before CORE can be ported at all.** M12 (`ROADMAP.md`) is date-triggered by the first 2027 alpha; if AdvantageKit has no 2027 branch by the beta, R18's contingency fires and CORE does not port. Nothing in this document can mitigate that — it is a dependency, not a design choice.

---

## 11. Testing strategy (release-blocking)

1. **Pure-math tests, no HAL.** `Reduction`, `Axis`, `SiDomain`, `MechanismUnits` (including `toSi`/`fromSi`/`siPerOutputRotation`), `ContinuousUnwrap`, `SafetyModel.route`, `SuperstructureReport`, `Superstructure` planning, `atGoal` predicate logic, config validation. These run in CI in milliseconds and cover the entire bug class the user's repos actually hit.
2. **Round-trip unit tests.** For a grid of reductions and geometries: `toUser(toOutputRotations(x)) == x` and `fromSi(toSi(x)) == x`. The 41-inline-conversion bug class dies here.
3. **`UnitsContractTest`.** Asserts the §4.1 table row by row. This is the test that would have caught revision 1's 57.3× `RIO_FULL` error **and** revision 3's raw-jerk and REV-`×60` errors, so revision 4 spells out every row rather than leaving "and so on" to an implementer:

   | Input | Expected device value | Catches |
   |---|---|---|
   | rotary `Gains.kP = 1.0` V/rad | `Slot0.kP == 2π` | the 57.3× `RIO_FULL` error |
   | linear `Gains.kP = 1.0` V/m | `Slot0.kP == metersPerOutputRotation` | the 39.37× inches error |
   | `kS`, `kG` | unconverted, volts on both sides | over-eager conversion |
   | `maxVelocity = 1.0` deg/s | `MotionMagicCruiseVelocity == 1/360` rot/s | |
   | `maxAcceleration = 1.0` deg/s² | `MotionMagicAcceleration == 1/360` rot/s² | |
   | **`jerk = 1.0` deg/s³** | **`MotionMagicJerk == 1/360` rot/s³** | **the raw-jerk bug (rev 3 gave 1.0)** |
   | **`jerk = 1.0` m/s³** on the §5.4 elevator | **`MotionMagicJerk == 1/0.279400 = 3.5791` rot/s³** | the same, linear |
   | `maxVelocity = 1.0` m/s on the §5.4 elevator, **REV** | `maxMotion.cruiseVelocity == 3.5791` (**not** 214.75) | **the ×60 bug** |
   | `maxAcceleration = 1.0` m/s², **REV** | `maxMotion.maxAcceleration == 3.5791` | the same |
   | goal velocity `1.0` output rot/s with `kV_si = 5.00` V/(m/s) | request `FeedForward == 5.00 × 0.279400 = 1.3970` V | a wrong `kV_device` in the §3.5.6 fold |
4. **`RioFullIsSiTest`.** Runs a `RIO_FULL` rotary mechanism in sim with gains in V/rad and asserts it settles within tolerance. Runs the same mechanism with revision 1's user-unit loop and asserts it does **not** — the regression net for the specific defect.
5. **`ArmHoldsStationTest`.** In sim, with `GravityMode.COSINE` and a nonzero `horizontalAt`, the arm holds station at **both +45° and −45°** with `kP = 0`. A sign error in `GravityArmPositionOffset` fails at one of the two angles; revision 1's sign error would fail this test.
6. **`GravityOffsetRangeTest`.** `RotaryAxis.arm(Degrees.of(95))` on a Phoenix backend produces a FATAL `ConfigError` naming ±0.25 rot.
7. **`SignalSubscriptionTest`.** Calls `configureSignals(SIMPLE, Tier.COMPETITION)` and asserts `inputs.supplyCurrentAmps` is a real number, `inputs.positionRot` is **`NaN`** (not `0.0`), and `inputs.closedLoopReferenceRot` is `NaN`. Plus a reflective boot assertion that every field a `Sink` writes is a declared `SignalSet` channel.
8. **`DeviceResetRecoveryTest`.** In sim, force `hasResetOccurred()`, assert the config is re-applied, `deviceResetCount` increments, the setpoint is re-sent within one loop, and an alert is raised. **Plus the boot case (rev 4):** after a clean construction and exactly one `periodic()`, assert `deviceResetCount == 0` and `<name>/device-reset` is **inactive** — the constructor consumed the power-on flag (§3.5.5), so a boot is not a reset.
9. **`SetpointHeartbeatTest`.** With an unchanged goal, assert `setControl` is called at least once per 100 ms and at most once per 100 ms.
10. **`ApplyGainsIsNonBlockingTest`** + the ArchUnit rule of §3.9 + a `RootstockTracer` budget assertion that `Mechanism/ApplyGains` stays under 1.0 ms at 10 Hz write-through.
11. **Vendor parity tests.** The same `PositionConfig` on `TalonFXMotorIO`, `SparkMotorIO`, `GenericMotorIO`, and `SimMotorIO`, run through the same profile in simulation, must land within the same tolerance. Includes `FieldLockedTurretParityTest`: the chassis-omega feedforward must be applied on **every** backend, by whichever mechanism that backend supports. The NEO path being untestable is a named defect in `0000-XXXX-Robot-Template`.
12. **Config-error snapshot tests.** Every message in §5.6 and §8.9 is asserted verbatim. A docs example that no longer compiles, or an error message that regresses, fails CI.
13. **`SafeModeBootsTest`.** A config with a FATAL error must produce a robot that constructs, runs the scheduler, publishes `/Rootstock/Driver/SafeMode = true` with the full error list, and refuses every command — **not** an `ExceptionInInitializerError`.
14. **`WithCopyDoesNotDoubleRegisterTest`.** `ELEVATOR.withGains(g).withReduction(r)` produces no CAN-ID conflict.
15. **`DescribeSnapshotTest`.** Constructs `RobotConfig.ELEVATOR` and `RobotConfig.ARM`, calls `describe()`, and **string-matches the §4.4 and §3.5.7 blocks verbatim**. This is the assertion Principle 11 promises; revision 1 shipped a `describe()` block that was internally inconsistent by a factor of 2.9, and revision 3 shipped one whose `kG` was low by 2.2× and whose gear ratio was wrong by 1.92×, and nothing caught either. **The expected blocks are the revision-4 recomputed ones** — `0.279400 m`, `0.0444679 m`, `5.0000 drum rot`, `2.251 m/s`, `kG 0.33`, `Slot0.kP 22.35`, `65.411:1`, `kG 0.29`, `kV 1.25`. A verbatim string match is what makes an arithmetic slip a build failure rather than a reviewer's lucky catch.
16. **`RouterBoundingBoxTest.` **A zone the diagonal misses but the L-shaped path enters must be detected. Plus `SynchronizedAxesRestoresDiagonalTest`.
17. **Every documentation snippet WILL BE extracted from a compiled, executed test — at M24, and it is release-blocking.** Stated in the future tense on purpose: **no code exists today, so this test does not exist today**, and revision 3 asserted it in the present tense while the covered snippets carried a 1.92× gear ratio, a deleted facade and a nonexistent vendor method (see the §9 preamble). It explicitly includes §9.3's `getAutonomousCommand()`: an AI-written docs error (`sin` where `cos` belonged) destroyed a reviewer's trust in a competing library in a single forum post, and revision 1's `.andThen(trigger::getAsBoolean)` was the same class of error — it compiled. Until M24, the substitute is a **manual re-derivation of every numeric block and every vendor call at each revision**, which is what revision 4 was; it found three defects the claim was supposed to have prevented, which is the argument for building the test rather than for trusting the claim.
17b. **`VendorRequestChainsCompileTest` (new in revision 4, release-blocking, and it gates the vendor adapters — not M24).** Every request-builder chain in §3.5.6, §3.7 and §6.5 is compiled against the real Phoenix 6 and REVLib jars, in a source file that exists solely to be compiled. `MotionMagicVoltage.withVelocity(...)` shipped in a document whose header promises every vendor API name was read from a javadoc; a header promise is not a mechanism, and this is the mechanism. It runs in the same CI job as the vendor-rename tables of §3.7, so a vendor's next rename fails the build instead of silently invalidating a table.
18. **Allocation test — an M5 gate condition** (dated gates G0–G5 deleted; see `ROADMAP.md` §5).** A loop of the **full §9 example robot** (three mechanisms + superstructure + telemetry) must allocate zero bytes after warmup. Not a synthetic mechanism. Loop overruns were attributed to competing libraries repeatedly in 2026 and the team response was to disable telemetry entirely.
19. **`forkEvery = 1`** in the test harness, because simulated CAN devices reject duplicate IDs within one JVM — a constraint all three user repos already carry.
20. **`InputsReplayRoundTripTest`** (new in revision 3). For each of `MotorInputs`, `AbsoluteEncoderInputs`, `GyroInputs`, `DigitalSensorInputs`: populate every field with a distinguishable value including `NaN` and an empty array, `toLog` into a `LogTable`, `fromLog` into a fresh instance, and assert field-by-field equality with `NaN`-aware comparison. Deterministic replay is now a *guaranteed* library property rather than a backend-dependent one (§1.1), and a guarantee needs a test. This also catches the classic hand-written-`toLog` bug: a field added to the class and to `toLog` but forgotten in `fromLog`, which replays silently as the default. A reflective assertion that every declared public field appears in both method bodies runs alongside it.
21. **Partial-adoption fixture compilation** (D29, unchanged by decision 3). The four fixture projects `tunables-only`, `one-mechanism-only`, `health-only`, `full` compile on every PR, and a check asserts that **only `full` references `RootstockRobot`**. This is what keeps §1.1a's public `RootstockLifecycle` honest now that there is only one base class left to be tempted by.
22. **`ReductionTeethOrderTest`** (new in revision 4). `Reduction.ofTeeth(58, 10).rotorPerOutput() == 5.8` and `ofTeeth(58,10).then(58,18).then(42,12).rotorPerOutput() == 65.411 ± 1e-3`. Revision 3's parameter names — `ofTeeth(int driving, int driven)` — would have made a faithful implementation return `10/58` and turned the flagship arm into a 65× speed-up. A library whose central promise is making gear-ratio errors unrepresentable does not get to have that one caught by review.
23. **`MaxMotionUnitsTest`** (new in revision 4, and it **gates the REV adapter's ship**). Drive a MAXMotion move in `SparkSim`, assert the observed steady-state profile velocity equals the configured `cruiseVelocity` within 5 %. §3.7's `× 60` was a 60×-too-fast profile on every REV elevator and arm, presented as settled next to a "Verified in the javadoc" rename claim that covered only the method's *name*. Also closes `DESIGN.md` §5.6's still-open item 3 (`SparkSim.iterate` velocity units).
24. **`HomingRestoresLimitsTest`** (new in revision 4, §6.3). A `MotorIO` stub whose `applyVerified` fails only on the restore call must leave the mechanism neutral, `isHomed()` false, `<name>/homing-limits-unrestored` active at `BLOCKS_MATCH`, and `Homing/LimitsRestoreVerified = false` in the log.
25. **`ErrorSchemaTest`** (new in revision 4). Assert that a `PositionMechanism` mid-profile publishes `Error == Setpoint − Measured` **and** `GoalError == Goal − Measured` as two distinct keys, with `Error` small and `GoalError` large during a long move on a `RIO_PROFILE_MOTOR_LOOP` axis. This is the `design/04` §3.1 contract, and revision 3 published one key with the wrong formula, which makes every in-flight move look like a tracking failure on the plot the tuning wizard reads.
26. **`AlertImpactCoverageTest`** (new in revision 4). Every alert key CORE can raise (the §1.3 table) is constructed with an explicit `MatchImpact`, and the set of `BLOCKS_MATCH` keys matches the table exactly. D10 forbids a default; this is what stops one being reintroduced by an overload.
27. **`CanBudgetArithmeticTest`** (new in revision 4). The frame-budget figures `describe()` prints are recomputed by the test from the subscribed channel list and the 140 bit/frame constant, and matched against §3.5.7's block. §3.5.1's *previous* number was wrong by ~19× and justified a default; a printed budget nobody checks is how that happens twice.

---

## 12. What we deliberately do NOT do

| We do not | Because it already exists |
|---|---|
| Write a logging framework or a replay system | **AdvantageKit**, which as of maintainer decision 3 is a **required dependency**, not one option among three. CORE writes through the `RootstockLog` tiered facade and implements `LoggableInputs` directly. DogLog and Epilogue are not supported and there are no adapters for them. |
| Write a swerve library | **YAGSL**, **CTRE `SwerveDrivetrain`**, WPILib kinematics/odometry. Drivetrain (`design/05`) composes with the Tuner-X-generated `TunerSwerveDrivetrain`; it does not replace it. |
| Write trajectory generation or path following | **PathPlannerLib 2026.1.2**, **Choreo**. Auto (`design/05`) wraps only the four things `AutoBuilder` needs from a drivetrain. |
| Write vision pose estimation or an AprilTag pipeline | **PhotonVision**, **Limelight/LimelightHelpers**, WPILib `SwerveDrivePoseEstimator`. |
| Write motion profiling math | WPILib `TrapezoidProfile` / `ExponentialProfile`, Phoenix **Motion Magic** (incl. Expo and Dynamic), REV **MAXMotion**. CORE picks which one runs and where, and never re-derives one. |
| Write PID or feedforward math | WPILib `PIDController`, `TrapezoidProfile`, `ElevatorFeedforward`, `ArmFeedforward`, `SimpleMotorFeedforward`; Phoenix `Slot0Configs`; REVLib `FeedForwardConfig`. `Controllers.Feedforward` is a **dispatcher**, not an implementation — the three WPILib classes have no common supertype, so something has to choose between them, and it is 40 lines. |
| Write physics simulation | WPILib `ElevatorSim` / `SingleJointedArmSim` / `FlywheelSim` / `DCMotorSim`, and **maple-sim** (optional) for game-piece contact. |
| Write system identification | WPILib `SysIdRoutine`. CORE wires it from the same config and adds the safety gates; it does not implement the regression. |
| Write a declarative state-machine framework for 2027 | **WPILib 2027 ships one** on Commands v3. `Superstructure` is a *mechanism coordinator* with interlocks and collision avoidance — a strictly narrower, robot-shaped thing — and it is designed to sit on top of whichever command framework is present. |
| Write a graph-search superstructure planner | 254 and 6328 do this better and we say so in §13 OQ #7. `SafetyModel` is a 2-axis geometric router, not an A* over a weighted digraph. |
| Write a tunable-number framework inside CORE | Tuning (`design/02`) owns it, and must re-point at **WPILib 2027's first-party Tunable API** when it lands rather than compete with it. |
| Write a dashboard | **Elastic**, **AdvantageScope**. CORE publishes NT4 structs and ships layout JSONs. |
| Re-model every vendor config knob | **`TalonFXConfiguration`** and **`SparkMaxConfig`** are the vendors' own fluent configs, maintained by the vendors. CORE models only what is physical, shared, or derivable, and passes everything else through `applyRaw()`. |
| Provide a unified control-execution API across on-motor and on-RIO loops | This is the specific mistake the community rejected. CORE shares *config data* and makes the *location* explicit. |
| ~~Force AdvantageKit, `LoggedRobot`, or an IO-layer architecture~~ — **this row is REVERSED by maintainer decision 3 and is no longer something we avoid** | We now require all three. `rootstock` depends on AdvantageKit; the one base class is `RootstockRobot extends LoggedRobot`; `MotorIO`/`MotorInputs` is the IO-layer architecture. What we still do not force is the *base class*: `RootstockLifecycle` is public and a team wires it into its own `LoggedRobot` (§1.1a). **Consequence, stated where an adopter will see it: a team already committed to DogLog or to plain Epilogue cannot adopt Rootstock without switching loggers, and a team on plain `TimedRobot` with no logging must adopt AdvantageKit before it can adopt even the health monitors.** Doc 04 carries the exclusion rows in the adoption matrix (M8). |
| Own field constants, alliance flipping, or game logic | Field/Auto domains. The 2027 field origin moves to the center of the field, so this must live behind an abstraction owned by one domain, not scattered. |

---

## 13. Open questions

1. **`TalonFXS` external feedback field names.** The `ExternalFeedback` config *group* is verified; the field names inside `ExternalFeedbackConfigs` (the `SensorToMechanismRatio` / `RotorToSensorRatio` analogues) are **[UNVERIFIED]**. Must be confirmed against the 26.x javadoc before `TalonFXSMotorIO.buildConfig` is written. Similarly, whether `Commutation.MotorArrangement` covers every motor the user's teams might attach.
2. **~~REVLib arbitrary feedforward and position-goal velocity.~~ CLOSED.** `SparkClosedLoopController.setSetpoint(double setpoint, ControlType, ClosedLoopSlot, double arbFeedforward, ArbFFUnits)` **exists — verified**, and recorded in `DESIGN.md` §5.6. `SparkMotorIO` therefore reports `arbitraryFeedforward() == true` and `positionGoalVelocity() == VelocityCarrier.DEVICE_FEEDFORWARD_VOLTS` (§3.7, §6.4). There is still **no** REV analogue of `PositionVoltage.withVelocity()`, which is why the carrier is the feedforward one — the same carrier the Phoenix Motion Magic path uses, with the same formula. *(Revision 3 left this open and reported both capabilities `false`; the integration pass had already closed it and this document had not caught up.)*
3. **REVLib fault/connection API — HALF CLOSED.** The fault-query names are **verified** (`hasActiveFault()`, `hasStickyFault()`, `hasActiveWarning()`, `hasStickyWarning()`, `getFaults()`, `getWarnings()`; `DESIGN.md` §5.6). What remains **[UNVERIFIED]** is whether REV exposes anything equivalent to `hasResetOccurred()`. That is the more important half: without it, the §3.5.5 reset-recovery guarantee is Phoenix-only, and `describe()` must say so on every REV mechanism rather than letting a reader assume parity.
4. **`DutyCycleEncoder` in 2027.** Does it survive the Counter/interrupt removal? If not, `FeedbackSpec.DioAbsolute` (the REV Through Bore path 9143-A depends on) is 2026-only and teams must move to a CANcoder or a SPARK-attached absolute encoder.
5. **SystemCore onboard IMU and Smart IO APIs** do not exist yet. `SystemCoreImuGyroIO` and any Smart IO digital-sensor backend are stubs. Do not guess method names.
6. **`StructGenerator.genRecord` type support** is undocumented. `MechanismConfigSnapshot` is flattened to primitives to avoid the risk; if `genRecord` turns out to support enums and nested records cleanly, the snapshot could become structured. Needs a test.
7. **Superstructure axis count — and an honest statement of the ceiling.**

   > **Rootstock's router is 2-axis and geometric, not a cost-weighted graph search.** It
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
   9143-A's CorAl, or whether true 3-D routing is needed. The 2-axis router is M14 and its gate is
   validation against 9143-A's ACTUAL CorAl geometry (R11). Pairwise decomposition over three axes
   is in no milestone and is therefore OUTSIDE v0.1; a real robot's zone set should decide whether
   it is ever needed.
8. **Should `Reduction` know about belt/chain slip?** A `Reduction` measured from tooth counts is exact; a real cascade elevator can differ by a few percent. A `calibrationScale` field would let a team correct measured travel without touching the tooth counts — but it is also a place to hide a wrong ratio. Leaning against; wants a decision.
9. **Follower disagreement threshold.** What is a sane default for a two-motor elevator before `<name>/follower-disagrees` fires? 9143-A has a hand-written "elevator sides out of sync" alert; the threshold there is robot-specific. Proposal: default to 2% of total travel with an override, and only alert while enabled.
10. **`GravityArmPositionOffset` availability on REV.** Phoenix 26.x has it, with a verified (−0.25, 0.25) rot range. REVLib's `kCos` is multiplied by the cosine of *absolute mechanism position* with no offset field mentioned in the docs — if there is no offset, the REV arm path requires the zero of the encoder to be at horizontal, or a RIO-side correction. **[UNVERIFIED]**, and it is the one place where the two vendors may not map cleanly onto one `GravityMode`. Note that the Phoenix ±90° limit already forces teams toward "zero at horizontal", so the two paths may converge in practice.
11. **Naming — now settled, for a reason that changed.** `MotorIO` vs `MotorPort` vs `MotorLink`. Revision 2 argued for `MotorIO` because it matches the AdvantageKit vocabulary 600+ teams already know, *while* noting that `MotorInputs implements RootstockInputs` kept the vocabulary without the dependency. Maintainer decision 3 removes the tension entirely: we **have** the dependency, `MotorInputs` **is** a `LoggableInputs`, and a team arriving from an AdvantageKit template finds the names, the `updateInputs(inputs)` shape and the `Logger.processInputs` semantics they already use. **Keep `MotorIO`/`MotorInputs`.** The one thing to confirm before the API freeze (M24) is the *log key* schema in §3.4, since those strings are the replay contract and changing one invalidates older logs.
12. **Does `Setpoint` need to be tunable per-robot?** A setpoint that differs between the practice bot and the comp bot is common. `PositionConfig.withSetpoint(name, value)` covers it, and setpoints are on the §1.2 tunable allowlist, but persisted per-robot tuning (tune on the practice bot, promote to the comp bot) is a Tuning-domain question that affects the `Setpoint` type's shape.
13. **`OutputMode.TORQUE_CURRENT` — where does it go now?** Landing it requires an amps-per-SI row in `design/02` §4.2's `GainSink` table, an amps-per-SI mode in `FeedbackDesigner`, a unit tag in `gains.json`, and a migration path for a team that tuned in volts. That is a coherent chunk of work, not a flag flip. **Revision 3: revision 2 filed it under "v0.2"; there is no v0.2, and it appears in no milestone M1–M24, so it is OUTSIDE v0.1** — a genuine reduction, not a deferral to a later release. The `ConfigError` in §3.5 that refuses it is therefore permanent for v0.1, not temporary, and its message must not promise a version that does not exist.
14. **Signal rate default — NOW BLOCKING, because revision 3's justifying arithmetic was wrong.** §3.5.1 raises position/velocity to 100 Hz to avoid aliasing against the 50 Hz loop. That rationale is sound. The claim that it was free — *"≈ 0.6 % of a 1 Mbps bus and immaterial"* — was **wrong by ~19×** (11.2 % against the claimed 0.6 %); the recomputed figure is **4.5 % per position-controlled motor**, and eight of them plus a swerve drivetrain at 60–80 % can saturate a `rio` bus. Revision 4 already narrows the default to `POSITION` mechanisms only and prints a real aggregate budget, but **the 100 Hz number itself must not be frozen without a measurement.** The measurement, specified so it can actually be run:
    * Instrument 8793's existing robot with `CANBus.getStatus()` (`BusUtilization`, `BusOffCount`, `TxFullCount`, `REC`/`TEC`) logged at 10 Hz for a full match-length run.
    * Record utilisation with the drivetrain alone, then with each mechanism's signals added, so the per-mechanism increment is measured rather than modelled.
    * Compare against §3.5.1's one-frame-per-signal upper bound; the ratio between them is the empirical answer to the **[UNVERIFIED]** Phoenix status-frame packing question, which is the largest unknown in the whole budget.
    * **Gate:** this measurement must land before M5's allocation/telemetry gate, because the default it sets is baked into every `describe()` snapshot and therefore into `DescribeSnapshotTest`.
15. ~~**What replaces `robotInit()` in 2027.**~~ **DOWNGRADED 2026-08-08 by `DESIGN.md` D13a + D29, and the cross-doc half is WITHDRAWN.** The question was posed because `RootstockRobot` overrode `IterativeRobotBase.robotInit()` — the library's single use of a 2027-removed hook. **D13a deleted that override** and D29 renamed the lifecycle method to `init()`, so Rootstock no longer touches the hook at all and rule 5 applies without a carve-out. Revision 5's request that `DESIGN.md` §8 scope rule 5 to *"no class outside `org.rootstock.core`, with `RootstockRobot` named as the documented exception"* is **withdrawn** — the exception has no subject. What survives is a plain compatibility note: whether WPILib 2027's `IterativeRobotBase` line or AdvantageKit's 2027 `LoggedRobot` offers an equivalent post-construction start callback is **[UNVERIFIED]** and unknowable until those exist, but Rootstock's design does not depend on the answer, because its post-construction work runs from a constructor-tail `init()` call and a lazy first-cycle fallback (§1.1a). M12's checklist, no longer gating.
16. ~~***(new, 2026-08-08 — the two contract requests §1.1b raises, filed here so they are not only inside a javadoc.)*** **`design/04` owes three answers on the `TelemetrySource` declaration half**~~ — **ALL THREE ANSWERED AND APPLIED, 2026-08-08. OQ16 is CLOSED in full: (a) confirmed CORE's assumption, (b) granted CORE's request, (c) overruled CORE on one of five.**

    **(a) The rotary declared unit — CORE was right; `design/04` §3.1's parenthetical was the error.** It now reads *"Meters for an elevator, **Degrees** for an arm"* and carries a paragraph naming the old wording a **57.3× mislabel over a degree stream**. **No conversion added.** §1.1b's `ROTATIONAL_RADIANS -> d.positionUnit(Degrees).velocityUnit(DegreesPerSecond)` is unchanged.

    **(b) The unit-free overload — granted.** `design/04` §1.1 declares **`TelemetryDescriptor extra(String key, Tier tier)`** and states that it is *not* interchangeable with the three-argument form: the three-argument form writes entry metadata AdvantageScope reads for axis labelling, the two-argument form asserts there is no unit to write. **Applied in §1.1b:** `DeviceResetCount`, `Blocked` and `Plan` moved onto it and their `Units.Value` arguments deleted; `FeedbackVolts`/`FeedforwardVolts` keep `Volts` on the three-argument form because they are measurements. The `Units.Value` workaround is gone from this document's descriptor calls.

    **(c) The tiers — four confirmed, `DeviceResetCount` overruled to CRITICAL, and BOTH halves applied together.** §1.1b declares `d.extra("DeviceResetCount", Tier.CRITICAL)` and §6.2 publishes `RootstockLog.critical(kDeviceResets, (long) …)`. The `design/04` §1.5 schema audit compares declaration against publish and fails if only one moves, which is why they are one edit and not two. **One correction to this question's own reasoning, recorded rather than quietly dropped:** it argued that *"a STANDARD key vanishes when the FMS gate raises `minimumTier`"*. **That is false.** `design/04` §2.7.1 raises `minimumTier` to STANDARD **and no higher** — the gate drops DEBUG and only DEBUG — so a STANDARD key is present in an FMS-attached log today. The real argument for CRITICAL is the one `design/04` gives: `minimumTier` is a `LogConfig` field a team can set to `CRITICAL` at an event under a byte-budget squeeze, and §2.2 promises CRITICAL keys *"are never removed from the schema by any mechanism"*. CORE's conclusion was right for a wrong reason, and the wrong reason is the part worth writing down.

    *(Superseded question text, verbatim, follows.)* CORE is written against a stated assumption for each rather than blocked on any of them.
    **(a) The rotary declared unit.** `design/04` §3.1's lead-in says *"the **declared** unit of the mechanism (Meters for an elevator, **Radians** for an arm)"*. CORE publishes user units, and a rotary axis's user unit is **degrees** (`Axis.userPerOutputRotation()`, `Axis.unitLabel() == "deg"`, §4.3). §1.1b therefore declares `Units.Degrees` / `Units.DegreesPerSecond`. If `design/04` means what it says, either that parenthetical changes or the publisher must convert — and converting would put radians on the wire for the one axis type where degrees are what a student reads. **CORE's assumption: `design/04`'s parenthetical is the error.**
    **(b) A unit-free `extra(...)` overload.** `TelemetryDescriptor.extra(String, Unit, Tier)` requires a `Unit`, but `DeviceResetCount` is a count and `Blocked`/`Plan` are `String`/`String[]`. CORE passes `edu.wpi.first.units.Units.Value` (a `DimensionlessUnit` — **verified** against the WPILib javadoc) because nothing else fits the signature. **Requested: `TelemetryDescriptor extra(String key, Tier tier)`.**
    **(c) The tiers of the five extras.** `DeviceResetCount`, `FeedbackVolts`, `FeedforwardVolts`, `Blocked` and `Plan` are absent from `design/04` §3.1's table, so §1.1b declares each at the tier its existing call site already uses (all **STANDARD**). That makes declaration and publish agree by construction, which is all the schema audit checks; it does not make the tier *right*. **`DeviceResetCount` is the one worth arguing about** — a STANDARD key vanishes when the FMS gate raises `minimumTier`, and "a motor controller rebooted mid-match" is a thing you want in an FMS-attached log. `design/04` confirms or overrules; if it overrules, the `extra(...)` line and the `critical`/`log` call move together.
17. ~~***(new, 2026-08-08)* `LogConfig`'s construction style — `design/04` §2.2 and `DESIGN.md` D13a currently disagree, and the disagreement is load-bearing rather than stylistic.**~~ **CLOSED 2026-08-08 — `design/04` §2.2b made the edit this question asked for, and it went the way CORE was already written.** `design/04` §2.2b now declares `LogConfig` as an **immutable value with `with*()` copies**, one per field, with **no public constructor, no setter, no public field and no Builder**, plus **exactly the two D29 factories** `defaults()` and `adoptExistingLogger()`; reading is by accessor. Its own rationale paragraph cites D13a in the same terms this question did — *"a config object that can be mutated after `start()` is a config object that can silently disagree with the log's own provenance metadata"* — and D29 for the factories. **`RootstockLog.configure(Consumer<LogConfig>)` is gone**; `design/04` §2.3's entry point is `RootstockLog.configure(org.rootstock.core.spi.LogConfig config)`, taking the value. **§1.1a's restatement did not have to change**, because it was written against D13a/D29 from the start, and that is the only reason this closes as a confirmation rather than as a rewrite. **The original text is struck through rather than deleted: it is the record of a disagreement that survived three revisions of two documents, and `DESIGN.md` §16 item 5(e) tracks it.** *(Superseded question text, verbatim, follows.)* `design/04` §2.2 declares `LogConfig` as a class of **public mutable fields**, reached through `RootstockLog.configure(Consumer<LogConfig>)`. **D13a** — binding, and later — requires an **immutable value with `with*()` copies**, in as many words: *"The config argument is an immutable `LogConfig` value built with `with*()` copies, not a `Consumer<LogConfig>`… a consumer implies a mutable config object, which principle 6 and D1a forbid everywhere else in the library."* **D29** further requires two factories, `LogConfig.defaults()` and `LogConfig.adoptExistingLogger()`, that `design/04` §2.2 does not declare at all — and those two are the entire mechanism by which "who calls `Logger.start()`" is an answered question instead of a double-start crash. §1.1a is written against **D13a/D29** and takes **`design/04` §2.2's field set** unchanged, which is the only split that satisfies both documents' ownership claims. **`design/04` owns the fix:** re-declare §2.2's fields behind `with*()` copies, add the two factories, and either delete `RootstockLog.configure(Consumer<LogConfig>)` or re-shape it to take a `LogConfig` value. Until that lands, `design/01` §1.1a's `LogConfig` block carries the divergence note inline rather than implying agreement. `DESIGN.md` §16 item 5(e).
18. ~~***(new, 2026-08-08)* Where does `LogConfig` live, given that `RootstockLifecycle` is in `org.rootstock.core` and rule 9 says every arrow points into core?**~~ **CLOSED 2026-08-08 — the recommended resolution was taken, CORE's half is applied, and the residues that kept this open "for this document only" are gone. The gate below is what stays; the count is struck through rather than deleted, because a dated count that was true when written is the record of how the sweep went.**

    **What landed.** `design/04` **§2.2b** declares `LogConfig` under `package org.rootstock.core.spi;` — **option 1**, the one §1.1a's blockquote recommended — with the rule-9 reasoning restated at the declaration site and an explicit statement that **telemetry still owns the field set and semantics; only the package moved**. `LogConfig` now sits alongside `MechanismGeometry` and `SimMotorHandle`, the two other pure immutable downward-crossing values D26 put there, which is the argument that made option 1 cheap.

    **CORE's half, which is the whole of this document's obligation.** Both `import` lines in §1.1a — `RootstockLifecycle`'s and `RootstockRobot`'s — now read `org.rootstock.core.spi.LogConfig`, and §1.1a's restatement block opens `package org.rootstock.core.spi;`. **No signature changed**, because `create(LogConfig)` and `RootstockRobot(LogConfig)` never spelled the package inline. §1.1a's blockquote is retitled **RESOLVED** and keeps the finding and all three candidates, because "why is a telemetry-owned type under `core.spi`" is a question the next reader will ask.

    **Verified by grep, 2026-08-08, not taken on report:** `grep -n "^ *import org\.rootstock\.telemetry\.LogConfig" design/01-core-mechanisms.md` returns **zero** — zero imports and zero declarations. The old package survives here only as prose, in §1.1a's blockquote and in this item.

    **The residue, and the gate is written to be import-shaped so it is not confounded by the prose that reports it.** The bare-string grep is useless as a gate: every document that correctly records the move adds a hit to it, so the count goes *up* as the design gets *more* correct — the same defect `DESIGN.md` §16 names in items 1, 4(f), 6 and 7. **The gate is therefore `grep -rn "^ *import org\.rootstock\.telemetry\.\(LogConfig\|Tier\|RobotMode\)" design/ DESIGN.md`, which must return zero.** ~~**It returns 3 as of this pass: `design/02` §14's end-to-end example (line 4784) and `design/06` (lines 3231 and 3282, the second a bare import with no comment).**~~ **All three were swept on 2026-08-08 by the pass that also moved `Tier` and `RobotMode`; the gate returns zero across `design/01`, `design/02`, `design/03`, `design/05` and `design/06`.** *(`DESIGN.md` §10A.4 carried a fourth and was fixed by a parallel pass while this item was originally written. `design/04` is not this pass's to edit and is not claimed clean here — it is the definition site, and its own rule-9 residue note is `DESIGN.md` §16 item 11's remaining subject, not this document's.)*

    **AMENDMENT, 2026-08-08 — the second arrow, and why the first fix was not sufficient.** Moving `LogConfig` to `core.spi` left rule 9 **still red**, because `LogConfig`'s own public signatures named telemetry types: `minimumTier()`, `withMinimumTier(Tier)`, `mode()`, `withMode(RobotMode)`. A `core.spi` type whose public members return `org.rootstock.telemetry.Tier` is the **same arrow out of core, one level down**. **Resolution taken: `enum Tier` and `enum RobotMode` move to `org.rootstock.core.spi`**, joining `MechanismGeometry`, `SimMotorHandle` and `LogConfig` — the D26/D31 shape for behaviourless downward-crossing value types. **`Demotable` does not move**: it is never named in a core signature. **A narrow named rule-9 allowlist was considered and rejected** on the ground that an allowlist that grows once grows again, and rule 9's whole value is being mechanically checkable with no judgement calls. **Telemetry keeps ownership of both enums' semantics; only the package moved.** This document's four import sites (§1.1a ×2 `LogConfig`, §1.1b `Tier`, §7.1 `MotorIOFactory` `RobotMode`) all read `core.spi`, and **no signature in this document changed**, because none ever spelled a package inline.

    *(Superseded question text, verbatim, follows.)* D13a puts `RootstockLifecycle`/`RootstockRobot` in **`org.rootstock.core`**; `design/04` §2.2 and `DESIGN.md` §10A.4's import line put **`LogConfig` in `org.rootstock.telemetry`**; and `create(LogConfig)` therefore names a telemetry type in a public core signature. **That is an arrow out of core, and ArchUnit rule 9 forbids it** — a type reference is the same violation as the direct call §1.1 already discusses, just quieter, and D28's one-jar packaging hides it without fixing it. §1.1a's blockquote states the three candidate resolutions and recommends the first: **move `LogConfig` into `org.rootstock.core.spi`**, the package D26 created for exactly this ("the types that cross a layer boundary downward"), alongside `MechanismGeometry` and `SimMotorHandle`, which are the same shape — pure immutable values with no behaviour. **`design/04` owns the type; `DESIGN.md` §8 owns the rule; CORE owns neither and has not moved anything.** This is not a blocker for M1 code — it compiles either way — but it is a blocker for rule 9 being green on the first CI run, and a rule that is red on day one is a rule that gets disabled.


# PumpkinLib

> ## ⚠️ STATUS: DESIGN STAGE. THERE IS NO CODE YET.
>
> This repository currently contains **design documents only** — **more than 20,000 lines** of them (**22,778** measured at the time of the six-lens expert review; re-measure with `wc -l README.md DESIGN.md DECISIONS.md ROADMAP.md design/*.md`, because the number grows at every revision and it is quoted here as a measurement, not an impression — earlier revisions said "about 20,000", which was a 14% understatement in the one sentence establishing the documents' scale). Revised twice: once after an adversarial four-lens review, and again after a six-lens independent expert review. No Java has been written. No artifact has been published. No vendordep URL resolves. No template repo exists. Every code block in these documents is a *specification*, not a *snippet you can run*.
>
> **Target: one release, `v0.1`, containing everything.** There is no beta, no phased rollout, and no 2027-season delivery. At the author's realistic solo pace, **`v0.1` is a 2030 release** (central estimate 2029-12, mid-2030 once annual WPILib carrying cost is counted). With two additional mentor-grade committers it is spring 2028. Nothing available makes it 2027. The arithmetic is in [`ROADMAP.md` §2](ROADMAP.md) and it is not softened there.
>
> **Nothing will be announced anywhere until `v0.1` is tagged.** Until then, work reaches only the author's own teams (8793, 9143) as untagged internal snapshots. If you found this repository, you found a plan, not a product.
>
> **Where the plan lives:** [`ROADMAP.md`](ROADMAP.md). **Why each choice was made:** [`DECISIONS.md`](DECISIONS.md). **The full design:** [`DESIGN.md`](DESIGN.md).

---

## ⚠️ What this does NOT do

**Read this before anything else in this file.** PumpkinLib is a Java library for FRC that pre-wires the tools a team already uses — AdvantageKit, PathPlanner, Choreo, PhotonVision, Limelight, Phoenix 6, REVLib, SysId, WPILib — into one coherent seam. That is the whole of what it is; here is what it is not, **before** any description of what it does.

**PumpkinLib removes software prerequisites. It removes none of the others.**

It will not make an unreliable intake reliable. It will not substitute for driver practice hours. It will not choose a good strategy, will not scout, and will not improve your build quality or your CAD pipeline.

Published analysis of the widening EPA gap between top and average FRC teams attributes the elite advantage primarily to **funding, in-house manufacturing capability, CAD maturity, supplier knowledge, driver repetitions, and a chain of five to ten subsystems that must all work** — "if you screw up one you are done." Software is the marginal differentiator on top of those, not a substitute for them.

**If your robot is mechanically unreliable, fix that first.** This library will only help you log the failure more precisely.

The one practice deficit PumpkinLib can partially substitute for is **robot-hours**, through simulation: a mechanism that homes, profiles and holds correctly in `simulateJava` is a mechanism you did not burn a Saturday debugging. It cannot substitute for driver reps at all.

**The honest ceiling.** A small team that adopts all of PumpkinLib should expect: fewer matches lost to *"the robot didn't move"* (the self-test), fewer matches lost to a mis-scaled or unhomed mechanism (units plus a boot-time derivation dump), a tuning loop measured in minutes instead of Saturdays, and autos that do more than one thing at a time. It should **not** expect to out-cycle a team with a better intake, better drivers, and forty more practice hours.

**And it does not exist yet.** Everything below is written in the present tense because that is how a specification reads. None of it is shipped. See the status banner.

---

## What it is

PumpkinLib is a Java library for FRC that gives a small team the **software substrate** elite teams build for themselves — pre-wiring the tools they already use into one coherent seam instead of reimplementing any of them.

You declare a mechanism as data: a gearbox, a drum radius, soft limits, current limits, named setpoints. You get, with no further code — a Phoenix 6 or REVLib backend with the closed loop running where it belongs, physics simulation that runs before the robot is built, homing, gravity compensation, interlocked superstructure transitions, live gain tuning over NetworkTables, a guided on-robot wizard that measures kS/kV/kA/kG and explains each step to the student running it, a documented telemetry schema, deterministic AdvantageKit replay, a one-button pit self-test, and seven competition-day health monitors that name their own failures in English.

It is deliberately **not a framework**. Every mechanism is a plain WPILib `Subsystem`. Every abstraction hands back the raw `TalonFX` on page one of the docs. You can adopt exactly one piece — the tuning system, say — onto a robot project you already have.

It ships in **two shapes**: a template repository you fork to get a working robot project in one command, and a versioned library underneath it that a one-line dependency bump can patch mid-season. The template is the front door; the library is the substance.

---

## The defensible core

Almost every individual capability here exists somewhere. AdvantageKit does replay. AdvantageScope does 3D. PathPlanner does paths. PhotonVision does vision and camera simulation. YAGSL does swerve-from-JSON. SysId does characterization. 254, 6328, 3061 and 4738 have all published excellent mechanism abstractions. **Nothing here is a research result.**

The product is the pre-wired whole, because the wiring is where small teams actually die. Eight things are genuinely defensible:

1. **The hardware seam is a *goal*, never a voltage.** `MotorIO.setPositionGoal(outputRotations, rps, arbFfVolts)` — so Motion Magic, FOC, `SensorToMechanismRatio`, fused CANcoders, setpoint latching and REVLib 2026's on-controller gravity feedforward all survive the abstraction. Every prior attempt at vendor neutrality put the seam at voltage and threw away everything a Kraken is worth.

2. **One gearbox type, one geometry type, one converter.** The reduction is applied exactly once, inside the vendor config. The geometry is applied exactly once. Nothing in your Java ever multiplies by a ratio. Change one number and gains scaling, soft limits, profile constraints, sim gearing, telemetry units, homing seeds and tolerances all follow.

3. **One `Gains` type, volts-per-SI, converted once.** The same steer motor's kP is ~7 on the roboRIO, ~100 in Phoenix, ~0.01 in REVLib. That 10,000× spread makes gains untransferable and makes teaching impossible.

4. **A guided tuning wizard, not a slider panel.** It runs SysId motion on-robot, fits kS/kV/kA/kG with streaming least squares, derives kP/kD from LQR and shows you the resulting ωn and ζ, **asks you to predict the outcome before it moves anything**, and writes plain-language coaching. This is the genuinely unoccupied niche in the FRC ecosystem.

5. **Config is data; failures are values, not exceptions.** Records, builders, `with*()` copies. Validation errors are *collected* and printed together, then the robot enters `SAFE_MODE` — it still boots. A CAN ID of 64 becomes a sentence, not an `ExceptionInInitializerError` at 11 p.m. before a competition.

6. **Sim-first, with no second code path.** Declaring mass or MOI is the *only* thing you write to get physics. Phoenix and REV simulate through their own vendor sim state, so simulation exercises the real ratio path. A unit mistake shows up in `simulateJava`, not on the field.

7. **The pre-match self-test and health monitors.** One button in the queue line: every mechanism moves, every sensor reports, every CAN device answers, every controller is in the right slot. PASS/FAIL per subsystem, in a dashboard you already have open. Elite teams lose almost no matches to "the robot didn't move"; small teams lose several per event. **No FRC library we surveyed packages this** — the nearest neighbours are pit-*checklist* tools rather than robot-code libraries: Team 135's **Squire** (which [`DESIGN.md` §4](DESIGN.md) names as the tool that proves the concept) and web checklist apps such as FRC PRECHECK. None of them runs the check on the robot, against the robot's own declared configuration. *(Earlier revisions said "nothing in the ecosystem packages this." That is an unfalsifiable absolute, and it is the exact construction our own hedging rule in [`DECISIONS.md`](DECISIONS.md) rewrote elsewhere. We can report a survey; we cannot report a proof of non-existence.)*

8. **Zero-mystery debugging as a design constraint, tested as one.** Every error names the mechanism, the field, the value, the expected range, and the fix. The release gate is a **CSA test**: a mentor who has never used PumpkinLib must diagnose three seeded faults from the driver station and the log alone, in under ten minutes.

**One item was withdrawn from this list.** Earlier revisions claimed a ninth: *installs as one vendordep with zero vendor `requires`, on kickoff morning, before any vendor has published.* AdvantageKit is now a required dependency, so that property is gone. See [Modules](#modules) — it is now a disadvantage relative to a WPILib-only library, not a differentiator.

---

## Support policy — read this before you depend on it

**Between January 9 and April 30, PumpkinLib accepts only additive fixes. Issues are triaged on Sundays. There is no guaranteed response within 48 hours of a competition. If PumpkinLib is blocking you at an event, use the rip-out procedure below.**

This is written by one mentor who runs two FRC teams and an FTC team. That is the bus factor, and pretending otherwise would be dishonest. A multi-year build makes it a *larger* bus factor than it used to be, not a smaller one — see risk R21 in [`ROADMAP.md` §9](ROADMAP.md).

What exists to protect you from it:

- **`pumpkin update --library <version>`** takes an in-season patch release into an already-forked template in about sixty seconds. It rewrites every `vendordeps/PumpkinLib*.json` as one atomic set, re-resolves, and prints the changelog delta. **It never touches a file under `src/`.** This is the first thing a stuck team should try, and it is the reason the library is versioned separately from the template.
- **`pumpkin doctor --bundle`** writes one zip with the boot dump, every mechanism's config snapshot, the full alert state, the last log's header and final 30 seconds, the resolved version matrix, the template drift table, and the git provenance — so a bug report is actionable with no back-and-forth.
- **A runtime kill switch.** Put `Elevator` in `src/main/deploy/pumpkin/disabled.txt` and that component drops to neutral, unregisters from every registry, and raises one INFO alert. **No code change, no redeploy of Java.** You keep driving.
- **`docs/removing-pumpkinlib.md`** shows the plain-WPILib equivalent of every PumpkinLib concept, side by side, per subsystem. A CI job compiles a fixture where one mechanism is hand-rolled and three are PumpkinLib, so the rip-out path is exercised, not just described.
- **Artifacts are mirrored to Maven Central**, so they stay resolvable if this org disappears. With BSD-3-Clause, anyone can fork and re-publish.
- **A second person has push access and has cut a release** before v0.1 ships. That is a release gate, not an aspiration.

The kill switch and the rip-out procedure get their first real use from the author's own teams, years before any stranger sees them. That is deliberate: a safety valve nobody has ever pulled is not a safety valve.

---

## Quickstart sketch

*None of this works yet. It is the target.*

### The front door: fork the template

`PumpkinTemplate` is a GitHub template repository. Use it, or clone it — you get a robot project that builds, deploys and runs headless simulation before you write a line.

```bash
pumpkin init --template swerve --team 8793 --vendor phoenix6
cd 8793-robot
./gradlew simulateJava
```

Three variants: `swerve` (CTRE swerve plus two mechanisms), `differential` (differential drive plus two mechanisms), `mechanism-only` (no drivetrain — the incremental-adoption path, made first-class).

> **`differential` is documented-absent until M15, and `pumpkin init` says so rather than pretending.** `DifferentialBackend` is built in M15; until then `pumpkin init --template differential` **fails with a named message and the milestone it is waiting on**, instead of generating a project that builds and does not drive. A front door that generates a broken project is worse than one with three doors and a sign on the third.

What you get in the fork: a pinned, coherent vendordep set — `WPILibNewCommands`, `AdvantageKit`, `PumpkinLib`, **and the adapter for the vendor you selected** (`--vendor phoenix6` → `PumpkinLib-Phoenix6.json`; `--vendor revlib` → `PumpkinLib-REVLib.json`; `--vendor both` → both), all pinned to one coherent version set; `Robot.java extends PumpkinRobot`; one worked `PositionConfig` elevator and one `SimpleConfig` intake; a `ControlMap` with a mandatory `MANUAL` mode; a `Superstructure` with two interlocks; an empty `disabled.txt` kill switch; a CI workflow that builds and runs headless sim; and `.pumpkin/template.lock`, a SHA-256 manifest of every file the template owns.

**`pumpkin init` deletes the vendor sets you did not pick**, and the lock manifest is written *after* that pruning — so a vendordep `init` removed is not in the manifest at all and `doctor --template` cannot mis-report it as `MISSING`. This matters more than it sounds: a Phoenix-only team that got REVLib installed anyway would be the exact failure [`DESIGN.md` §8](DESIGN.md) criticises in YAGSL, on our own advertised path. *(If you actually want both — so that moving one mechanism to a NEO in week 4 is a config change rather than a vendordep install — that is `--vendor both`, one flag.)*

Two commands manage it from then on:

```bash
pumpkin update --library 2026.0.3   # in-season patch: touches vendordeps only, never src/
pumpkin doctor  --template          # classifies every template-owned file:
                                    # UNCHANGED | MODIFIED-BY-TEAM | MISSING | ADDED
```

`pumpkin update --template` three-way merges **only** files you have not edited; for everything else it writes the upstream patch to `docs/template-drift/` with a one-line explanation and asks you to apply it by hand. **Template updates are opt-in and never automatic**, and `doctor --template` is a report, never a gate. Drift is expected — you edit `RobotContainer.java` on day one, by design.

### The other door: add the library to a project you already have

WPILib → *Create a new project* → **Template · Java · Command Robot**, then *Manage Vendor Libraries → Install new libraries (online)* → the AdvantageKit vendordep **first**, then `https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json`.

This is the supported path for an existing repo you are not going to re-fork. It is second in this document on purpose: the template is what actually gets a team from zero to a moving simulation, and a vendordep URL never has.

### Either way, the whole elevator is this

```java
public static final PositionConfig ELEVATOR = PositionConfig.linear("Elevator")
    .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
                      .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED))
    .reduction(Reduction.ofStages(3.0, 4.0))            // change THIS and everything follows
    .axis(LinearAxis.sprocket(Inches.of(0.25), 22, /* cascade stages */ 2))
    .feedback(new FeedbackSpec.RotorOnly())
    .softLimits(Inches.of(0.0), Inches.of(55.0))
    .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
    .gains(Gains.UNTUNED)                                // run the wizard; see below
    .constraints(MotionConstraints.of(/* m/s */ 1.6, /* m/s^2 */ 6.0))
    .tolerance(Inches.of(0.5), 0.05, 0.06)
    .homing(HomingStrategy.currentSpike()
        .direction(HomingStrategy.Direction.REVERSE).voltage(Volts.of(-1.5))
        .currentThreshold(Amps.of(30)).debounce(Seconds.of(0.15))
        .timeout(Seconds.of(4.0)).backoff(Inches.of(0.5)).seedTo(Inches.of(0.0)))
    .setpoint("STOW", Inches.of(0.0)).setpoint("L4", Inches.of(52.5))
    .sim(Pounds.of(24.0), Inches.of(0.0))                // the ONLY sim code you write
    .build();
```

```java
public static final PositionMechanism ELEVATOR = new PositionMechanism(RobotConfig.ELEVATOR);
// ...
PumpkinRegistry.addAll(ELEVATOR);          // ONE call. Telemetry, health, self-test, tuning.
m_driver.y().onTrue(ELEVATOR.goTo("L4"));
m_driver.start().onTrue(ELEVATOR.homeCommand());
```

**`Gains.UNTUNED` is not a placeholder you leave in.** In simulation it resolves to a physics-derived first guess from your declared mass, so the demo moves, and the boot dump says so. **On real hardware, a mechanism with `UNTUNED` gains refuses closed-loop control** and tells you to run the wizard. We do not ship another team's converged gains as pasteable literals.

Plan **two hours** for your first session, not thirty minutes. [`DESIGN.md` §11](DESIGN.md) walks it honestly, in four blocks, including the 8–20 minute cold GradleRIO build.

**The escape hatch is on page one, not in an appendix:**

```java
ELEVATOR.io().as(TalonFXMotorIO.class)
    .ifPresent(io -> io.talonFX().setControl(new DynamicMotionMagicVoltage(...)));
```

---

## Adopting one piece

Partial adoption still works, and it is still a first-class path. **But it now has one hard prerequisite it did not used to have: AdvantageKit.**

You do **not** have to fork the template, adopt the drivetrain layer, or convert more than one mechanism. You **do** have to be on AdvantageKit, because that is where `PumpkinLog` writes and it is not swappable. A team already on AdvantageKit already extends `LoggedRobot` and loses nothing:

```java
public class Robot extends LoggedRobot {                   // yours, unchanged, if you're on AKit
  private final PumpkinLifecycle m_pumpkin;
  private final RobotContainer m_container;

  public Robot() {
    // adoptExistingLogger(), NOT defaults(): your code already called Logger.start().
    // There is no no-argument create() -- starting the Logger twice is a crash, so the
    // library refuses to guess which of the two situations you are in.
    m_pumpkin = PumpkinLifecycle.create(LogConfig.adoptExistingLogger());
    m_container = new RobotContainer();                    // your existing construction
    m_pumpkin.init();                                      // last, after everything registers
  }

  @Override public void robotPeriodic() {
    m_pumpkin.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_pumpkin.afterUserPeriodic();
  }
  @Override public void disabledInit()  { m_pumpkin.disabledInit(); }
}
```

That gets you alerts, health monitors, match context, robot identity, the self-test sequencer and the dashboard layout server. Your drivetrain, vision and autos are untouched and unaware. (`PumpkinRobot extends LoggedRobot` is a ~20-line shim over exactly this; manual wiring stays documented first.)

**Two things worth noticing in that snippet.** There is **no `robotInit()`** — PumpkinLib initializes in constructors, which is WPILib's recommended shape and is enforced on library code by an ArchUnit rule; your own `Robot` may still override `robotInit()` if you have one, and nothing here interferes. And the `LogConfig` factory is **`adoptExistingLogger()`**, not `defaults()`: `defaults()` means *"PumpkinLifecycle owns AdvantageKit's `Logger` — configure and start it"* and is what `PumpkinRobot` uses, while `adoptExistingLogger()` means *"attach; my code already started it."* Getting that wrong is a double `Logger.start()`, which fails at boot rather than degrading, which is why there is no defaulted no-argument `create()`.

Staying on plain `TimedRobot` also works — call `Logger.start()` yourself, then `create(LogConfig.adoptExistingLogger())` and drive `PumpkinLifecycle` the same way — and you get the entire list above. **What you give up is deterministic replay**, because AdvantageKit's replay driver needs `LoggedRobot` to own the loop. PumpkinLib's own code stays replay-safe either way; you just cannot exercise it. Ranking, honestly: `PumpkinRobot` → your own `LoggedRobot` → `TimedRobot` with replay given up.

**Who can and cannot take this path:**

| A team currently running | Can adopt one piece? |
|---|---|
| **AdvantageKit** | **Yes.** This is the intended case, and nothing above changes for you. |
| **Nothing / `SmartDashboard` only** | **Yes** — but you inherit AdvantageKit's IO-layer discipline and its opinions along with the tuning wizard you came for. That is a real pedagogical cost and it is not optional. |
| **DogLog** | **No, not without switching loggers.** A hard incompatibility, not a migration path. DogLog is widely used precisely because it is cheap to adopt; leaving it for a tuning wizard is a large ask and some teams will decline. |
| **Plain Epilogue** (WPILib first-party) | **No, not without switching loggers.** This one gets worse over time, because Epilogue is the first-party path and where WPILib is investing. |

Want **only** the tuning system on a subsystem you already wrote? Implement `TuningTarget` in about 30 lines. `TuningTarget` lives in core precisely so this works without adopting the mechanism layer *or* the wizard — but the AdvantageKit prerequisite above still applies.

[`DESIGN.md` §11c](DESIGN.md) has the full adoption matrix — one row per piece, with minimum artifacts, minimum code, what you must *not* also do, and how to remove it, plus the two exclusion rows above. **Every row is compiled in CI.**

---

## Modules

Two things are published, plus the adapters.

| Artifact | Depends on | Vendordep |
|---|---|---|
| **`PumpkinTemplate`** — the GitHub template repo, three variants, the advertised front door | pins one coherent version set below | not a vendordep — you fork it |
| **`pumpkinlib`** — everything except vendor adapters | WPILib **+ AdvantageKit 26.0.2+** | `PumpkinLib.json` — `requires`: **`WPILibNewCommands.json` and `AdvantageKit.json`** |
| `pumpkinlib-phoenix6` | Phoenix 6 26.x | `PumpkinLib-Phoenix6.json` |
| `pumpkinlib-revlib` | REVLib 2026.0.x | `PumpkinLib-REVLib.json` |
| `pumpkinlib-photonvision` | photonlib 2026.3.4 | `PumpkinLib-PhotonVision.json` |
| `pumpkinlib-pathplanner` | PathplannerLib 2026.1.2 | `PumpkinLib-PathPlanner.json` |
| `pumpkinlib-choreo` | ChoreoLib 2026.0.3 | `PumpkinLib-Choreo.json` |
| `pumpkinlib-maplesim` | maple-sim 0.4.0-beta | `PumpkinLib-MapleSim.json` |
| `pumpkinlib-gradle` | — | Gradle Plugin Portal `dev.pumpkinlib.gradle` |
| `pumpkinlib-cli` | desktop only, never deployed | not a vendordep |

**AdvantageKit is a required dependency, not one logging backend among several.** It is the substrate: `PumpkinRobot extends LoggedRobot`, and `PumpkinLog` writes to `Logger` directly. There is no `LogBackend` SPI, and therefore no `pumpkinlib-advantagekit` and no `pumpkinlib-doglog` artifact — an AdvantageKit-specific artifact makes no sense when the core already requires it.

**What that buys:** deterministic replay is a **guaranteed** property of the library rather than a backend-dependent one. "PumpkinLib code is replay-safe" becomes unconditionally true instead of true-if-you-picked-the-right-backend, which is what makes `PumpkinReplayVerify` and the replay-safety lint deliverable at all.

**What it costs, stated plainly and not buried:**

- **You cannot install PumpkinLib until AdvantageKit has published for the season.** The earlier "zero *vendor* `requires`, installable on kickoff morning before any vendor has published" property is **gone**, and it was a real property with a real supporting fact behind it — AdvantageKit's 2026 swerve templates shipped weeks late waiting on vendors. That constraint has not gone away. We have chosen to sit on the wrong side of it.
- **If AdvantageKit does not ship for WPILib 2027, PumpkinLib does not ship.** Risk R18 is **accepted, not mitigated** — the escape hatch that used to downgrade it no longer exists. The contingency is decided in advance, armed on a date (the WPILib 2027 beta, ~Dec 2027), and numbered in **execution order** in [`ROADMAP.md` §4.2](ROADMAP.md) and [`DESIGN.md` §13.1](DESIGN.md): **tier 1 — contribute** (offer the port upstream first); **tier 2 — fork**, publishing a minimal `pumpkinlib-akit-compat` with a stated public intent to delete it the day upstream ships; **tier 3 — wait**, and say so publicly on this first screen before anyone adopts.
  **Tier 2 is legally clear, and we checked rather than hedged.** *Verified 2026-08-08:* AdvantageKit's `LICENSE` is **BSD-3-Clause**, `Copyright (c) 2021-2026 Littleton Robotics` — redistribution and modification are permitted provided the copyright notice, condition list and disclaimer are retained, so the fork is allowed **with attribution**. Its third clause is a **non-endorsement** clause naming Littleton Robotics and the marks *Mechanical Advantage* and *AdvantageKit*, which is why the artifact is named `pumpkinlib-akit-compat` and credits upstream in prose rather than in its coordinates. Earlier revisions left this `[UNVERIFIED]` in five places while building a three-tier plan around the uncertainty; the check took under a minute. **Residual:** this verifies `main` in 2026. Re-reading `LICENSE` on the 2027 branch is an explicit item in the trigger check.
- **Teams already on DogLog or plain Epilogue cannot adopt without switching loggers.** See the table in [Adopting one piece](#adopting-one-piece).
- **The addressable population shrinks** to AdvantageKit teams plus greenfield teams, and it shrinks further every year Epilogue improves. Decision 3 buys a guarantee and pays for it with reach.

**The template costs something too.** Every library release has to regenerate the template, re-pin the version set, and pass a **9-job CI matrix (3 OS × 3 variants)** *before* it can be tagged — including releases where only a vendor version moved. Two of the three variant rows run `build` + headless sim; the `differential` row asserts that `pumpkin init --template differential` fails with its documented-absent message until M15, and becomes a build + sim job after it, so the job count is nine throughout. The matrix runs at the default `--vendor phoenix6`; the `revlib` and `both` selections are covered by `pumpkin init` unit tests and by M3's vendor-parity gate rather than by the release matrix, which is a stated gap and not a hidden one. Budgeted at ~0.1 pw per release, which at an in-season patch cadence is roughly **0.5 pw per season of pure template tax**. That is a real fraction of a solo season.

`SimulatedLimelight` lives in `pumpkinlib-photonvision`, not the Limelight path, because it needs `PhotonCameraSim`. A Limelight-only team that wants simulation installs the PhotonVision vendordep. This is stated here rather than discovered at runtime.

---

## What ships when

**Everything ships in v0.1. No domain is deferred, and there is only one release.** Vision (Limelight, PhotonVision, custom coprocessors, object detection, `SimulatedLimelight`), the full drive funnel including differential, the AutoStep DSL on PathPlanner **and** Choreo, `OdometryReport`, 3D visualization, all six wizard recipes, the collision-avoidance router, the replay-safety lint, shoot-on-the-move, maple-sim and `CycleStats` are all v0.1 scope. The old v0.1/v0.2/v0.3/v0.4 split is **deleted as a release plan**; it survives only as internal build order.

### The maintainer chose complete scope over an early release

This was a deliberate trade, made with the calendar in front of us, and if you are reading this to decide whether to wait for PumpkinLib, here is what it means for you. A one-stop-shop library that arrives whole is more useful than four partial releases that each require re-learning what changed — but it arrives **years** later, and the author's own teams get nothing from PumpkinLib for the 2027 season. That cost was accepted, not overlooked. The upside is real and worth naming: with no external users there is no API stability obligation and no in-season support obligation, so breaking changes stay free right up to the tag, and the design keeps getting better instead of getting frozen early to protect strangers. The downside is equally real: **v0.1 will arrive as a very large first release from an unknown author**, which is the worst possible shape for a first read by a skeptical community. Do not plan a season around this library. If you need something in 2027, use AdvantageKit's templates, YAGSL, PathPlanner and SysId directly — which is what PumpkinLib wires together anyway.

### The schedule, not softened

Full scope is **68.5–79.5 person-weeks** net (midpoint **74.0**, used for every date below). One mentor running two FRC teams and an FTC team sustains **0.25–0.60 pw per calendar week**. That is 114 to 318 calendar weeks — roughly **2.2 to 6.1 years**.

| Capacity | v0.1 lands (central) | Best case | Worst case |
|---|---|---|---|
| **Solo (maintainer only)** | **2029-12-08** — **mid-2030** once the 2–4 pw/yr WPILib and template carrying cost is added | 2028-10-14 | 2032-09-10 |
| +1 mentor-grade committer | 2028-09-12 | 2027-12-25 | 2030-05-29 |
| +2 committers | 2028-04-07 | 2027-09-11 | 2029-08-24 |
| + a student team of 3 (no extra mentor) | 2029-03-06 | 2028-03-28 | 2031-09-05 |
| +2 committers **and** a student team of 3 | **2027-11-10** | 2027-07-04 | 2028-07-03 |

**v0.1 lands after the 2027 kickoff, and at solo pace after the 2028 and 2029 ones — probably after the 2030 one too.** Hitting the 2027 kickoff would take 3.34 pw/week. **That is six to eight full-time engineers, and here is the conversion rather than the assertion:** 3.34 pw/wk ÷ 0.6–0.7 focused pw per full-time engineer-week (meetings, reviews, context switching) = 4.8–5.6 FTE of raw output; grossed up for a 25–30% coordination tax at that team size (÷ 0.70–0.75) = **6.4–8.0 engineers**. That is not a scheduling problem to be optimized away; it is the size of what was asked for.

**A note on the rates behind that table.** The net rates are gross minus a coordination tax, **rounded to the nearest 0.05, and every rounding happens to favour the maintainer** (`1.95 × 0.70 = 1.365`, published `1.45`). Recomputed on the exact products, the central dates move **later** by 0 days (solo — no tax), ~19 days (+1), ~5 days (+2), ~45 days (student team) and ~33 days (+2 and students: **2027-11-10 → 2027-12-13**). **The conclusion survives — 2027-12-13 is still before the 2028-01-08 kickoff** — but a document whose defence is "the arithmetic is checkable" should let you check it. The exact products are printed in [`DESIGN.md` §12.2](DESIGN.md) and [`ROADMAP.md` §2](ROADMAP.md).

Two honest notes on that table. **Only the last row lands v0.1 before a kickoff.** And **a student team alone is worse than it looks** — its worst case (2031-09) is worse than solo's central case, because students are net-negative for three to six months on a codebase with ArchUnit-enforced package rules, unit-correctness contracts and safety-critical voltage code where the maintainer is the only reviewer. They are a good bet for docs fixtures, CI, `CycleStats`, `ValueExporter` and template variants; never for anything that commands a voltage.

Scope can no longer be cut by domain, so only two levers remain, and they are very different sizes. **Depth-within-domain** recovers 18.9 pw across nineteen named levers — firing *all* of them moves the solo date only from 2029-12 to 2029-02, about ten months. **Capacity is the real lever.** [`ROADMAP.md` §6](ROADMAP.md) lists every depth lever with what it costs you, in firing order, and names six things that are never reduced at any capacity.

### Milestones, not dates

Delivery is **milestone-gated**. A milestone is complete when its gate condition is demonstrably true, and it lands on whatever date the capacity above puts it. Solo dates below are derived from cumulative pw at the solo central rate of **0.42 pw/week** and are shown because that is the honest default; at +2 committers everything through M11 lands roughly a year earlier (M8 2027-01, M11 2027-04).

| # | Milestone | pw | Cum. | Solo | Usable by 8793 / 9143 mid-season |
|---|---|---|---|---|---|
| **M1** | Platform spine, lifecycle, competition day | 3.5 | 3.5 | Oct 2026 | **Yes — the biggest win, first.** Alerts, seven health monitors, `MatchContext`, `RobotIdentity`, `CanIdRegistry`, one-button `SelfTest`, bolted onto existing robot code with no other adoption |
| **M2** | Units, config, validation | 1.6 | 5.1 | Oct 2026 | Yes — `describe()` at boot, `vendorConfigDump` in every log |
| **M3** | The hardware seam, both vendors | 2.2 | 7.3 | Dec 2026 | No — it is a seam, not a feature |
| **M4** | Mechanisms + superstructure | 2.2 | 9.5 | Jan 2027 | Yes — mechanism conversion, ~1,600 lines deleted from 8793 |
| **M5** | Telemetry, physics sim, test harness | 2.2 | 11.7 | Feb 2027 | Yes — students work with the robot in pieces |
| **M6** | Tunables + persistence + Elastic | 1.1 | 12.8 | Mar 2027 | Yes — live tuning that persists and exports paste-ready Java |
| **M7** | Wizard core, ELEVATOR + FLYWHEEL | 3.0 | 15.8 | Apr 2027 | Yes — **but one week *after* the 2027 season ends.** At +2 committers: Dec 2026, before kickoff |
| **M8** | `PumpkinTemplate`, distribution, docs v1 | 2.8 | **18.6** | Jun 2027 | **First internally distributable snapshot.** 9143-B forks the template, 9143-A stays hand-wired — the A/B pair the drift tooling needs. The `swerve` and `mechanism-only` variants build and simulate on three OSes here; `differential` is documented-absent until M15 |
| **M9** | Drive funnel, CTRE backend, field and alliance | 5.0 | 23.6 | Aug 2027 | Yes — alliance handling and `OdometryReport` without adopting the funnel |
| **M10** | Vision core: Limelight | 4.0 | 27.6 | **Mar 2028** ‡ | Yes — both teams run Limelights; a scoring-accuracy change |
| **M11** | Auto DSL, PathPlanner **and** Choreo | 3.5 | **31.1** | **May 2028** ‡ | Yes — `.budget()` and `.skipToAfter()` make a 15-second auto degrade gracefully. **Competition-complete internal build** |
| **M12** | The WPILib 2027 port | 8.0 | 39.1 | **date-triggered** — arms at the ~Oct 2027 alpha and *preempts whatever is in flight*, which at solo pace is **mid-M10**, not "after M11" | Both team repos port with the library, rehearsing the migration |
| **M13** | Wizard completion (ARM, TURRET, STEER, DRIVE_VELOCITY) | 3.5 | 42.6 | Jul 2028 | Yes — arm/turret tuning plus a belt-tension check no FRC library we surveyed does |
| **M14** | Superstructure router + self-test DSL | 2.0 | 44.6 | Aug 2028 | Gated on 9143-A's real CorAl geometry |
| **M15** | Drive backends 2–5, traction, navigation | 4.5 | 49.1 | Oct 2028 | Differential backend makes the template's differential variant real |
| **M16** | Vision, advanced sources | 4.5 | 53.6 | Jan 2029 | PhotonVision, multi-camera arbitration, object tracking |
| **M17** | `SimulatedLimelight` + custom coprocessors | 3.5 | 57.1 | Mar 2029 | Gate: round-trip through the **production** decode path, validated against a real LL4 |
| **M18** | Shoot-on-the-move | 1.5 | 58.6 | Mar 2029 | Gate: convergence in 3 iterations, documented failure mode |
| **M19** | 3D visualization | 2.0 | 60.6 | May 2029 | |
| **M20** | Replay safety, enforced | 2.5 | 63.1 | Jun 2029 | Deliverable *only* because the template can add an `annotationProcessor` line a vendordep cannot |
| **M21** | Headless auto validation + maple-sim | 2.5 | 65.6 | Jul 2029 | |
| **M22** | Power, pneumatics, LED, haptics | 1.5 | 67.1 | Aug 2029 | |
| **M23** | Match analytics + code generation | 1.8 | 68.9 | Sep 2029 | `CycleStats` — the only loop that turns practice time into a measured number |
| **M24** | **v0.1 release hardening → the tag** | 5.1 | **74.0** | **2029-12-08** | CSA gate, second committer, API freeze, 3-OS install smoke test |

**‡ M10 and M11 move because M12 preempts them, and the roadmap now shows that rather than stating a rule it ignored.** At solo pace M9 ends 2027-08-31 and M10 is still in flight when the 2027 alpha arms in October, so the port cuts in **mid-M10**: M12 completes ~Feb 2028, M10's remainder ~Mar 2028, M11 ~May 2028. **The cumulative pw is identical, so M13 onward and the 2029-12-08 release date are unchanged** — only the ordering moves. The derivation is in [`ROADMAP.md` §5.1](ROADMAP.md). Read every date in this table as carrying **at least ±25%**, which is the band [`DESIGN.md` §13](DESIGN.md) R22(c) already conceded and which the roadmap now states instead of ±7%.

**M12 is the only date-triggered milestone.** It **arms at the first WPILib 2027 alpha (~Oct 2027)**, must not *start* later than the beta (~Dec 2027), and **preempts whatever is in flight** — which at solo pace means mid-M10, because M9 completes 2027-08-31. **It cannot *finish* inside the beta window at solo pace, and that is stated rather than wished away:** 8.0 pw ÷ 0.425 pw/wk = 18.8 weeks against a 5–8 week window, so completing inside it needs ≥1.00–1.60 pw/wk — the +2-committer configurations. At solo pace the port lands around **mid-February 2028**, after the season has started and after the community's porting window has closed, which is when those questions were cheap. The re-derived solo ordering is in [`ROADMAP.md` §5.1](ROADMAP.md); the **M24 date is unchanged**, because the total work is identical and only its position moves. After M12 the project is single-line: the 2026 source line and the rename generator are *deleted*, because there are no external users on the 2026 line to protect and maintaining two lines would be a permanent 20–30% tax paid to nobody.

**The hard gate that makes the internal-milestone story real:** every milestone M1–M11 has a written team-usable definition, and a milestone is **not complete** until both 8793's and 9143's repos build and pass `simulateJava` against it. Before M8 they consume PumpkinLib as a Gradle composite build; from M8 onward as `2026.0.0-SNAPSHOT-M<n>` from GitHub Pages. No public vendordep URL exists and nothing is posted to Chief Delphi before M24.

**And the honest problem with that story:** the library competes with the teams for the same hours from the same person. M1 through M6 are net-positive — each deletes team code, catches a bug class at construction, or removes a tuning Saturday. From M7 onward it goes net-negative if the wizard is not yet working, because the teams are carrying a half-finished dependency through a competition season. The rule that follows: **if a milestone's integration cost to 8793 or 9143 exceeds its benefit during a season, the teams pin to the last good snapshot and skip it until the offseason.**

At each kickoff (2028-01-08, 2029-01-06, 2030-01-05) an **annual relevance review** is answered in writing before work resumes: has the ecosystem filled the gap (WPILib's first-party Tunable PR, YAMS, a community wizard)? Is AdvantageKit still the right required dependency, or has Epilogue closed the replay gap and turned that decision into pure cost? Do 8793 and 9143 still consume the snapshots? A three-to-four-year runway carries two risks the old plan never had: **R20 relevance decay** (High, unmitigable except by shipping sooner) and **R21 maintainer continuity** (High; BSD-3-Clause plus Maven Central mirroring are the answers, and both are now decided).

---

## Conventions

Java 17 · GradleRIO 2026.2.1 · WPILib 2026.2.2 · Commands v2 · private fields `m_fieldName` · constants `kConstantName` · WPILib Units (`Measure`) at every config boundary · packages `org.pumpkinlib.*` · Maven group `dev.pumpkinlib`.

Development stays on the 2026 line through M11 and does **not** chase alphas; a 6-class `compat` package plus `org.pumpkinlib.field` plus **ArchUnit rules 2 and 12** (no year-volatile API outside `compat`/`field`; no preview features) confine every volatile API. WPILib 2027 renames `edu.wpi.first.*` → `org.wpilib.*`, drops NT3, and moves to SystemCore and Java 25; that port is M12.

**No Java preview features, ever, on the 2026 line.** The 2027 port is promised to be an import rewrite, and a preview feature makes that false.

---

## License

**BSD-3-Clause** — see [`LICENSE`](LICENSE). Copyright (c) 2026 PumpkinLib contributors.

Chosen to match WPILib exactly, so a team can vendor a single file into their own repo with no legal question, and so anyone can fork and re-publish if this project stalls. On a multi-year build by one maintainer, that second property is not a formality — it is half the answer to the continuity risk.

---

## Contributing

Not yet. There is nothing to contribute to.

But note what the schedule above actually says: **capacity is the only lever that moves the date meaningfully.** One mentor-grade committer moves v0.1 from 2029-12 to 2028-09. Firing every depth reduction in the plan moves it about ten months. If this design interests you, that is the honest ask.

When there is code, the first three rules will be:

1. **Every fenced Java snippet in every doc — including its import block — is extracted from a compiled, executed test.** Documentation correctness is a release blocker. One AI-written `sin`/`cos` error destroyed a competing library's credibility in a single forum post, and revision 1 of our own design shipped a 1.92× gear-ratio error in its headline example.
2. **A pull request that adds something from the [Non-Goals table](DESIGN.md#4-non-goals) is closed with a link to that row.** We integrate best-in-class tools; we do not reimplement them.
3. **Anything that commands raw voltage gets a second reviewer, permanently.**

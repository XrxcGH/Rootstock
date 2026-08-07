# PumpkinLib

> ## ⚠️ STATUS: DESIGN STAGE. THERE IS NO CODE YET.
>
> This repository currently contains **design documents only** — about 20,000 lines of them, revised once after an adversarial four-lens review. No Java has been written. No artifact has been published. No vendordep URL resolves. Every code block in these documents is a *specification*, not a *snippet you can run*.
>
> **First target:** `2026.0.1-beta`, feature-frozen **2026-12-06**, released **2026-12-20** — three weeks before 2027 kickoff.
> **Where the plan lives:** [`ROADMAP.md`](ROADMAP.md). **Why each choice was made:** [`DECISIONS.md`](DECISIONS.md). **The full design:** [`DESIGN.md`](DESIGN.md).

---

## What it is

PumpkinLib is a Java library for FRC that gives a small team the **software substrate** elite teams build for themselves — pre-wiring the tools they already use into one coherent seam instead of reimplementing any of them.

You declare a mechanism as data: a gearbox, a drum radius, soft limits, current limits, named setpoints. You get, with no further code — a Phoenix 6 or REVLib backend with the closed loop running where it belongs, physics simulation that runs before the robot is built, homing, gravity compensation, interlocked superstructure transitions, live gain tuning over NetworkTables, a guided on-robot wizard that measures kS/kV/kA/kG and explains each step to the student running it, a documented telemetry schema, deterministic AdvantageKit replay, a one-button pit self-test, and seven competition-day health monitors that name their own failures in English.

It is deliberately **not a framework**. Every mechanism is a plain WPILib `Subsystem`. Every abstraction hands back the raw `TalonFX` on page one of the docs. You can adopt exactly one piece — the tuning system, say — onto a robot project you already have, without changing your `Robot` base class.

---

## ⚠️ What this does NOT do

Read this before the capability list.

**PumpkinLib removes software prerequisites. It removes none of the others.**

It will not make an unreliable intake reliable. It will not substitute for driver practice hours. It will not choose a good strategy, will not scout, and will not improve your build quality or your CAD pipeline.

Published analysis of the widening EPA gap between top and average FRC teams attributes the elite advantage primarily to **funding, in-house manufacturing capability, CAD maturity, supplier knowledge, driver repetitions, and a chain of five to ten subsystems that must all work** — "if you screw up one you are done." Software is the marginal differentiator on top of those, not a substitute for them.

**If your robot is mechanically unreliable, fix that first.** This library will only help you log the failure more precisely.

The one practice deficit PumpkinLib can partially substitute for is **robot-hours**, through simulation: a mechanism that homes, profiles and holds correctly in `simulateJava` is a mechanism you did not burn a Saturday debugging. It cannot substitute for driver reps at all.

**The honest ceiling.** A small team that adopts all of PumpkinLib should expect: fewer matches lost to *"the robot didn't move"* (the self-test), fewer matches lost to a mis-scaled or unhomed mechanism (units plus a boot-time derivation dump), a tuning loop measured in minutes instead of Saturdays, and autos that do more than one thing at a time. It should **not** expect to out-cycle a team with a better intake, better drivers, and forty more practice hours.

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

7. **The pre-match self-test and health monitors.** One button in the queue line: every mechanism moves, every sensor reports, every CAN device answers, every controller is in the right slot. PASS/FAIL per subsystem, in a dashboard you already have open. Elite teams lose almost no matches to "the robot didn't move"; small teams lose several per event. Nothing in the ecosystem packages this.

8. **Zero-mystery debugging as a design constraint, tested as one.** Every error names the mechanism, the field, the value, the expected range, and the fix. The release gate is a **CSA test**: a mentor who has never used PumpkinLib must diagnose three seeded faults from the driver station and the log alone, in under ten minutes.

---

## Support policy — read this before you depend on it

**Between January 9 and April 30, PumpkinLib accepts only additive fixes. Issues are triaged on Sundays. There is no guaranteed response within 48 hours of a competition. If PumpkinLib is blocking you at an event, use the rip-out procedure below.**

This is written by one mentor who runs two FRC teams and an FTC team. That is the bus factor, and pretending otherwise would be dishonest.

What exists to protect you from it:

- **`pumpkin doctor --bundle`** writes one zip with the boot dump, every mechanism's config snapshot, the full alert state, the last log's header and final 30 seconds, the resolved version matrix, and the git provenance — so a bug report is actionable with no back-and-forth.
- **A runtime kill switch.** Put `Elevator` in `src/main/deploy/pumpkin/disabled.txt` and that component drops to neutral, unregisters from every registry, and raises one INFO alert. **No code change, no redeploy of Java.** You keep driving.
- **`docs/removing-pumpkinlib.md`** shows the plain-WPILib equivalent of every PumpkinLib concept, side by side, per subsystem. A CI job compiles a fixture where one mechanism is hand-rolled and three are PumpkinLib, so the rip-out path is exercised, not just described.
- **Artifacts are mirrored to Maven Central**, so they stay resolvable if this org disappears.
- **A second person has push access and has cut a release** before v0.1 ships. That is a release gate, not an aspiration.

---

## Quickstart sketch

*None of this works yet. It is the target.*

```bash
java -jar pumpkin-cli.jar init --team 8793 --example elevator --vendor phoenix6
./gradlew simulateJava
```

Or, by hand: WPILib → *Create a new project* → **Template · Java · Command Robot**, then *Manage Vendor Libraries → Install new libraries (online)* → `https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json`.

Then the whole elevator is this:

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

You do **not** have to change your `Robot` base class, adopt the drivetrain layer, or convert more than one mechanism.

```java
public class Robot extends TimedRobot {                    // or LoggedRobot. Yours. Unchanged.
  private final PumpkinLifecycle m_pumpkin = PumpkinLifecycle.create(LogConfig.nt4());

  @Override public void robotInit()     { m_pumpkin.robotInit(); /* your existing init */ }
  @Override public void robotPeriodic() {
    m_pumpkin.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_pumpkin.afterUserPeriodic();
  }
  @Override public void disabledInit()  { m_pumpkin.disabledInit(); }
}
```

That gets you alerts, health monitors, match context, robot identity, the self-test sequencer and the dashboard layout server. Your drivetrain, vision and autos are untouched and unaware.

Want **only** the tuning system on a subsystem you already wrote? Implement `TuningTarget` in about 30 lines. `TuningTarget` lives in core precisely so this works without adopting the mechanism layer *or* the wizard.

[`DESIGN.md` §11c](DESIGN.md) has the full adoption matrix — one row per piece, with minimum artifacts, minimum code, what you must *not* also do, and how to remove it. **Every row is compiled in CI.**

---

## Modules

| Artifact | Depends on | Vendordep |
|---|---|---|
| **`pumpkinlib`** — everything WPILib-only | WPILib | `PumpkinLib.json` — `requires`: **`WPILibNewCommands.json` and nothing else** |
| `pumpkinlib-phoenix6` | Phoenix 6 26.x | `PumpkinLib-Phoenix6.json` |
| `pumpkinlib-revlib` | REVLib 2026.0.x | `PumpkinLib-REVLib.json` |
| `pumpkinlib-photonvision` | photonlib 2026.3.4 | `PumpkinLib-PhotonVision.json` |
| `pumpkinlib-pathplanner` | PathplannerLib 2026.1.2 | `PumpkinLib-PathPlanner.json` |
| `pumpkinlib-choreo` | ChoreoLib 2026.0.3 | `PumpkinLib-Choreo.json` |
| `pumpkinlib-advantagekit` | AdvantageKit 26.0.2 | `PumpkinLib-AdvantageKit.json` |
| `pumpkinlib-doglog` | DogLog 2026.5.0 | `PumpkinLib-DogLog.json` |
| `pumpkinlib-maplesim` | maple-sim 0.4.0-beta | `PumpkinLib-MapleSim.json` |
| `pumpkinlib-gradle` | — | Gradle Plugin Portal `dev.pumpkinlib.gradle` |
| `pumpkinlib-cli` | desktop only, never deployed | not a vendordep |

**Zero *vendor* `requires`.** The core artifact depends on WPILib alone, so you can install PumpkinLib on kickoff morning before any vendor has published — which is a real constraint: AdvantageKit's 2026 swerve templates shipped weeks late waiting on vendors.

`SimulatedLimelight` lives in `pumpkinlib-photonvision`, not the Limelight path, because it needs `PhotonCameraSim`. A Limelight-only team that wants simulation installs the PhotonVision vendordep. This is stated here rather than discovered at runtime.

---

## What ships when

| Version | Target | Contains |
|---|---|---|
| **v0.1** | Dec 2026 | Platform + alerts + **seven health monitors** + **self-test**; units + config + validation; the hardware seam on Phoenix 6 **and** REVLib; mechanisms + superstructure interlocks + homing; telemetry (NT4 + AdvantageKit) + physics sim + test harness; tunables + persistence; **Wizard Lite** (two recipes); distribution + docs + `pumpkin init`/`doctor` |
| **v0.2** | Jan–Apr 2027, additive only | Drive funnel + CTRE backend + alliance handling + `OdometryReport`; Limelight vision + the 19-reason filter chain + `alignToTag`; PathPlanner auto + the `AutoStep` DSL; the remaining four wizard recipes; collision-avoidance router; 3D visualization; match cycle-time analytics |
| **v0.3** | May–Aug 2027 | Replay-safety lint; `SimulatedLimelight`; PhotonVision; object detection; shoot-on-the-move; traction/slip limiting; headless auto validation; more drive backends; Epilogue + DogLog backends; maple-sim |
| **v0.4** | Oct 2027 – Jan 2028 | The WPILib 2027 line: `org.wpilib.*`, Java 25, SystemCore, Commands v3 adapter |

**Vision, the drive funnel and the auto DSL are deliberately *not* in v0.1.** Every team already has a working drivetrain (Tuner X, an AdvantageKit template, YAGSL), working vision (the AdvantageKit vision template, LimelightHelpers) and working autos (PathPlanner's GUI and docs). The tuning wizard has no substitute. Deferring the three domains that *have* alternatives, in favour of the one that does not, is the whole ordering principle — see [`ROADMAP.md`](ROADMAP.md).

**The schedule is not comfortable and we are not going to pretend it is.** The full design is 68–79 person-weeks. Capacity before 2027 kickoff is 5.5–13. v0.1 is capped at 13.25 with a pre-committed cut list and five dated gates.

---

## Conventions

Java 17 · GradleRIO 2026.2.1 · WPILib 2026.2.2 · Commands v2 · private fields `m_fieldName` · constants `kConstantName` · WPILib Units (`Measure`) at every config boundary · packages `org.pumpkinlib.*` · Maven group `dev.pumpkinlib`.

**No Java preview features, ever, on the 2026 line.** The 2027 port is promised to be an import rewrite, and a preview feature makes that false.

---

## License

TBD — see [open questions in `DECISIONS.md`](DECISIONS.md#open-questions-for-the-maintainer). The intent is a permissive license (BSD-3-Clause, matching WPILib) so a team can vendor a file without a lawyer.

---

## Contributing

Not yet. There is nothing to contribute to. When there is, the first three rules will be:

1. **Every fenced Java snippet in every doc — including its import block — is extracted from a compiled, executed test.** Documentation correctness is a release blocker. One AI-written `sin`/`cos` error destroyed a competing library's credibility in a single forum post, and revision 1 of our own design shipped a 1.92× gear-ratio error in its headline example.
2. **A pull request that adds something from the [Non-Goals table](DESIGN.md#4-non-goals) is closed with a link to that row.** We integrate best-in-class tools; we do not reimplement them.
3. **Anything that commands raw voltage gets a second reviewer, permanently.**

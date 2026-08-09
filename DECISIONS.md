# PumpkinLib — Decision Log

Every major fork, the choice, what was rejected, and why. **These are binding.** A pull request that re-litigates one is closed with a link to its row. If a decision turns out to be wrong, it gets a new row that supersedes the old one — rows are never edited in place, so the reasoning survives.

**Status:** design stage, revision 3 (post maintainer decisions, 2026-08-07). Revision 2 was the adversarial-review pass, 2026-08-07.
**Format:** `MDn` = **maintainer decisions**, Part 0 — these outrank everything below them. `Dnn` = integration decisions from [`DESIGN.md` §5](DESIGN.md#5-integration-decisions--conflicts-reconciled) (ownership of a duplicated type or concept). `Ann` = architecture decisions from [`DESIGN.md` §14](DESIGN.md#14-design-decisions--rejected-alternatives). `Rnn` = decisions made *in response to* the adversarial review. `Xnn` = reviewer recommendations we **rejected**.

**Supersession convention:** a superseded row is never deleted. It gets a banner — **SUPERSEDED by MDn (2026-08-07)** or **AMENDED by MDn (2026-08-07)** — a one-line reason, and its original text is left standing below the banner. *Superseded* means the conclusion is reversed. *Amended* means the conclusion stands but a clause inside it no longer holds.

---

# Part 0 — Maintainer decisions, 2026-08-07

**These four are the highest-authority rows in the document.** They were taken by the maintainer after the adversarial review, with the cost of each stated to him in advance and accepted. Everything in Parts 1–4 that contradicts them is superseded or amended below, with the original reasoning left intact.

The consolidated consequence of all four is [`ROADMAP.md`](ROADMAP.md), which replaces `DESIGN.md` §12 in its entirety.

**Net effect on effort:** the adversarial-review plan was **68.25 – 79.25 pw** ("68–79" in `DESIGN.md` §12.1). MD3 removes **−1.25 pw**; MD2 adds **+1.50 pw**. New total: **68.5 – 79.5 pw net, midpoint 74.0**, which is the figure every date in `ROADMAP.md` is computed from. Not counted in the 74.0: the MD3 fork contingency (~2.5 pw) and the annual WPILib-and-template carrying cost (2–4 pw per calendar year from M8 onward).

---

## MD1. Scope — everything ships in v0.1. No domain is deferred.

**As stated by the maintainer, verbatim:**

> **Everything ships in v0.1. No domain is deferred.**

**What that includes, so it cannot be quietly narrowed later:** vision (Limelight + PhotonVision + custom coprocessors + object detection + `SimulatedLimelight`); the drive funnel with all backends including differential; the `AutoStep` DSL against **both** PathPlanner and Choreo; `OdometryReport`; 3D visualization; **all six** wizard recipes; the collision-avoidance router; replay-safety lint; shoot-on-the-move; maple-sim; `CycleStats`. All of it is v0.1.

**Alternatives rejected:**

| Rejected | Why the maintainer rejected it |
|---|---|
| The revision-2 plan: v0.1 = platform + mechanisms + tuning wizard (13.25–16.25 pw), with vision, drive and auto in v0.2 and the router in v0.3 | A staged release means a team adopting PumpkinLib in 2027 gets a library that cannot drive, see or run an auto, and has to re-adopt twice. The one-stop-shop property *is* the product; a v0.1 that is a tuning library competes with SysId, not with the four repos in the comparison table. |
| Shipping v0.1 on a date and cutting whatever did not fit | This is what the pre-committed cut list (C1–C5) was for. It optimizes for a date the maintainer does not actually need to hit, at the cost of shipping a library with a documented hole in it. |
| Deferring only the two most expensive domains (vision at ~12 pw, drive at ~9.5 pw) | Those are the two domains where the incremental-adoption story is weakest — a team cannot half-adopt a drivetrain — so deferring them defers most of the value while keeping most of the risk. |

**Rationale, as given:** the library exists to be the thing a small team installs *once*. A release plan that makes them install it three times over three seasons converts the single strongest property of the design into its weakest.

**The honest cost — stated to the maintainer before he decided, and accepted:**

1. **He was shown the two-to-six-year solo estimate and chose complete scope anyway.** The number put in front of him was: 74.0 pw midpoint against a sustainable solo rate of **0.25–0.60 pw per calendar week** for one mentor running two FRC teams and an FTC team. That is **114 to 318 calendar weeks — roughly 2.2 to 6.1 years** — central **2029-12-08**, or **mid-2030** (2030-04-08 to 2030-09-03) once the 2–4 pw/year carrying cost is included. He chose the scope with that number on the table. This row exists so that nobody, including the maintainer in 2028, can claim the schedule was a surprise.
2. **v0.1 lands after the 2027 kickoff (2027-01-09), and at solo pace after the 2028, 2029 and probably 2030 kickoffs too.** 8793 and 9143 get **no released PumpkinLib for the 2027 season.**
3. **Hitting the 2027 kickoff would require 3.34 pw/week — six to eight full-time engineers.** That is not a scheduling problem to be optimized away. It is the size of what was asked for.
4. **Only two levers survive, and they are very different sizes.** Domains can no longer be cut, so the levers are **(a) capacity** and **(b) depth within a domain**. The full depth-lever list (`ROADMAP.md` §6, L1–L19) recovers **18.9 pw** — about 25% of scope — and moves the solo central date only from 2029-12-08 to **2029-02-02**. Ten months. **Capacity is the large lever:** +1 committer → 2028-09-12; +2 → 2028-04-07; +2 and a student team → 2027-11-10, which is the only staffing in the table that lands v0.1 before a kickoff.
5. **A student team alone is worse than it looks.** Its worst case (2031-09-05) is worse than solo's *central* case. Students are net-negative for three to six months on a codebase with ArchUnit-enforced package rules, unit-correctness contracts and safety-critical voltage code where the maintainer is the only reviewer. They are a good bet for docs fixtures, adoption fixtures, CI, `CycleStats`, `ValueExporter` and template variants — **never M3, M4, M7, M9, M10, M14 or anything that commands a voltage.**
6. **A three-to-four-year runway introduces two risks the prior plan never carried:** **R20 relevance decay** (High, unmitigable except by shipping sooner — WPILib's first-party `Tunable` PR #7773 becomes *likely* rather than speculative over that window) and **R21 maintainer continuity** (High — R15's mitigations were sized for a one-season absence, not a multi-year one). MD4 plus Maven Central mirroring are the real answers to R21.
7. **R4 is deferred, not reduced.** Nothing is announced before M24, so a hostile first read cannot happen early — but v0.1 then arrives as a **very large first release from an unknown author**, which is the worst possible shape for that first read.

**What replaces the deleted machinery:**

- The **v0.1 / v0.2 / v0.3 / v0.4 split is deleted as a release plan.** It survives only as internal build order.
- The **five dated gates G0–G5 are deleted** and replaced by **capability-defined milestones M1–M24** (`ROADMAP.md` §5). A milestone completes when its gate condition is demonstrably true, on whatever date the capacity puts it. Gate remapping, for readers of the old rows: **G1 → the M3 vendor-parity gate** (and the sim-to-hardware transfer item in M5); **G2 → the M5 measured-p95 gate** (and the M7 fault-injection gate); **G4 → M24**; **G5's CSA test → M24**.
- The **pre-committed cut list C1–C5 is deleted** and replaced by the nineteen in-domain **depth levers** (`ROADMAP.md` §6), fired cheapest-capability-loss-first.
- **Milestones M1–M11 each carry a mandatory "team-usable" definition**, and a milestone is **not complete until both 8793's and 9143's repos build and pass `simulateJava` against it.** This is the hard gate that makes `DESIGN.md` §11b's incremental-adoption story true rather than aspirational, and it is how the maintainer's own teams get value during the 2027 season despite no release existing.
- **An annual relevance review** at each kickoff (2028-01-08, 2029-01-06, 2030-01-05), answered in writing before work resumes (`ROADMAP.md` §7.3).

**What is never reduced, at any capacity:** M1's seven health monitors and `SelfTest` sequencer; M2's three-tier validation; vendor parity in M3; M4's gravity/limits/homing hardening; M7's twelve abort conditions and the fault-injection test; M24's CSA gate and second-committer requirement. Those are either the product or the reason nobody gets hurt.

**Supersedes:** R9, X5, X6 (as release plans); amends A5, D28, R6, R8, R11 (gate and version references). See the index in Part 0.5.

---

## MD2. Delivery — both a library and a template repo, with the template as the primary front door.

**As stated by the maintainer, verbatim:**

> **Both a library AND a template repo, with the TEMPLATE as the primary front door.**

A team forks or clones **`PumpkinTemplate`** and has a working robot project immediately. The library remains the substance underneath, published as versioned Maven artifacts, so an in-season fix reaches a team as a **dependency bump rather than a merge**.

**Alternatives rejected:**

| Rejected | Why |
|---|---|
| **Library only** (a vendordep URL and docs) — the revision-2 plan | A vendordep gets a team a jar, not a working project. Everything the design promises about "two hours to a simulated robot" depends on a pre-wired `build.gradle`, a pinned coherent vendor version set, a `Robot.java` that already extends the right class, and a worked example config. Docs that tell a team to assemble that by hand are the failure mode `pumpkin init` exists to delete. |
| **Template only** (fork it, the code is in the fork) — the AdvantageKit-template / YAGSL-template shape | An in-season bug fix then arrives as a **merge into a fork the team has already edited**. Week 4 of build season, no team does that merge. This is the single most important reason the library must stay a versioned artifact. |
| **Template that vendors the library source** | Same failure as above, plus every team runs a different silently-diverged copy of the safety-critical code, and `pumpkin doctor --bundle` can no longer report a meaningful version. |

**Rationale, as given:** onboarding and maintenance want opposite things. A template wins onboarding; an artifact wins maintenance. Doing both is the only shape where a rookie team gets a working project in one command *and* gets a patch in sixty seconds.

**How it works (full detail in [`ROADMAP.md` §3](ROADMAP.md)):**

- **Template contents:** `build.gradle` / `settings.gradle` / `gradlew*` / `.wpilib/` on GradleRIO 2026.2.1 + Java 17 with `dev.pumpkinlib.gradle` applied and `pumpkinCheckDeploy` wired into `deploy`; `vendordeps/` holding `WPILibNewCommands.json`, `AdvantageKit.json`, `PumpkinLib.json`, `PumpkinLib-Phoenix6.json`, `PumpkinLib-REVLib.json` **all pinned to one coherent version set**; `src/main/java/frc/robot/` with `Robot.java extends PumpkinRobot`, `RobotContainer.java`, `Constants.java`, one worked `PositionConfig` elevator, one `SimpleConfig` intake, a `ControlMap` with a `MANUAL` mode (D30) and a `Superstructure` with two interlocks; `src/main/deploy/pumpkin/` with `disabled.txt` (the R15 kill switch), a schema-stamped empty `gains.json` and the generated Elastic layout; a `build.yml` running `build`, headless `simulateJava` and `pumpkinCheckDeploy`; `.pumpkin/template.lock`; and `docs/UPDATING.md` written out **in the fork, offline**.
- **Three variants** via `pumpkin init --template`: `swerve`, `differential`, `mechanism-only`. The last one makes `DESIGN.md` §11b's incremental-adoption path first-class rather than a documented workaround.
- **Version pinning:** `.pumpkin/template.lock` records the template version, the library version, the full vendor version matrix, and a **SHA-256 manifest of every file the template owns**. That manifest is what makes drift detection possible at all.
- **Taking a patch from inside a fork:** `pumpkin update --library 2026.0.3` rewrites the `version` field in **every** `vendordeps/PumpkinLib*.json` as one atomic set (mismatched adapter versions being the most likely self-inflicted breakage), updates the lock, re-resolves, runs `pumpkinCheckDeploy` and prints the changelog delta. **It never touches a file under `src/`.** Sixty seconds, and it is the only supported in-season upgrade path.
- **Drift management:** `pumpkin doctor --template` compares the fork against the pinned template version using the lock manifest and classifies every template-owned file as `UNCHANGED` / `MODIFIED-BY-TEAM` / `MISSING` / `ADDED` — a table, not a diff dump. `pumpkin update --template` three-way merges **only** `UNCHANGED` files and writes every other upstream change to `docs/template-drift/<file>.patch` with a one-line explanation. **Template updates are opt-in and never automatic**, and `pumpkin doctor --template` is a **report, never a gate** — a team must be able to ignore it forever and keep working. The drift table is included in `pumpkin doctor --bundle`, so a bug report says which template-owned files were modified with zero back-and-forth.

**The honest cost:**

1. **+1.5 pw one-time**, counted in the 74.0 (template authoring, the lock manifest, `update --library`, `update --template`, `doctor --template`, `docs/UPDATING.md`, the nine-job CI matrix and its fixtures).
2. **~0.1 pw per release, forever, and it cannot be skipped.** Every library release **regenerates the template** and re-pins the version set — a template pinned to a version that no longer exists is worse than no template. Every release runs the **nine-job template CI matrix** (3 OS × 3 variants × build + headless sim) **as a release gate**. Every vendor bump (Phoenix 6, REVLib, AdvantageKit, WPILib, GradleRIO) forces a regeneration and a matrix run **whether or not the library changed**.
3. At the in-season cadence the support policy implies — a patch every two to three weeks between January and April — that is **~0.5 pw per season of pure template tax**, a real fraction of a solo season's capacity. It is counted in the 2–4 pw/year carrying cost, which is **not** in the 74.0.
4. **Three variants triples the surface** that must clean-install on three OSes. Depth lever L3 (−0.5) drops to one variant, and its cost is honest: differential and mechanism-only teams then hand-assemble from docs, which hurts exactly the rookie teams the template exists for.
5. **A second front door is a second place to be wrong.** A team can now be broken by the library, by the template, or by the interaction between a hand-edited fork and a library bump. `pumpkin doctor --template` exists precisely because that third category is otherwise undiagnosable over a forum thread.

**Amends:** A4(e) (the "a vendordep cannot add an `annotationProcessor` line" premise — still true of the *vendordep*, now false of the *template*), D28 (`pumpkinlib-gradle` becomes load-bearing for onboarding, not just for `pumpkinCheckDeploy`), R8, R10.

---

## MD3. AdvantageKit is a required dependency.

**As stated by the maintainer, verbatim:**

> **AdvantageKit is a required dependency. It is NOT one backend among four. This is a reversal of the prior design.**

`PumpkinLib.json` declares `requires: [ WPILibNewCommands.json, AdvantageKit.json ]`. AdvantageKit is the logging and replay substrate, not an option.

**Alternatives rejected:**

| Rejected | Why |
|---|---|
| The revision-2 design: a **`LogBackend` SPI** with `Nt4LogBackend`, `EpilogueLogBackend`, `DogLogLogBackend` and `AdvantageKitLogBackend`, core depending on WPILib only | Deterministic replay was then a property of *one* backend, so every claim about replay had to be hedged with "if you chose AdvantageKit." `PumpkinReplayVerify`, the replay-safety lint, the `Clock.now()`-only discipline and the cycle-counted health scheduler were all conditional on a runtime choice the team made in one line of `RobotContainer`. A guarantee that depends on a config flag is not a guarantee. |
| Keeping the SPI but **defaulting** to AdvantageKit | Same problem, with a worse failure mode: the property silently disappears for the minority who changed the default, and the docs cannot honestly state it either way. |
| **Epilogue only** (WPILib first-party) | Epilogue does not do replay today. It is where WPILib is investing, which is exactly why R20 and the annual relevance review name it — but it does not do the thing the library is built around. |

**Rationale, as given:** deterministic replay is the property that makes every other honesty claim in the library checkable. It has to be unconditional.

**What collapses (−1.25 pw):**

| Was | Becomes |
|---|---|
| `PumpkinRobot extends TimedRobot` in core **+** `PumpkinLoggedRobot extends LoggedRobot` in a separate artifact (D13, revised by D29) | **One class: `PumpkinRobot extends LoggedRobot`.** `PumpkinLifecycle` stays public — D29 survives intact, and it is *why* this collapses cleanly. |
| `LogBackend` SPI + the four backend implementations | **Deleted.** `PumpkinLog`'s tiered facade writes to `Logger` directly. `pumpkinlib-advantagekit` and `pumpkinlib-doglog` do not exist. |
| `PumpkinInputs` / `PumpkinLogTable` mirroring AdvantageKit's types | **Deleted.** IO layers implement `LoggableInputs` directly — one less indirection between a `MotorInputs` field and the log. |
| `mode = REPLAY` refused with an actionable message when the backend cannot replay | **Deleted.** Replay always works. |
| D26's `ServiceLoader.load(LifecycleHook.class)` for every hook | **Drastically simplified.** D28 already put telemetry, tuning, sim and vision in one jar, so the compile cycle D26 existed to break is now broken by package structure alone. `PumpkinLifecycle.create()` builds an explicit priority-ordered hook list in code. `ServiceLoader` survives **only** for genuinely out-of-jar adapters (`phoenix6`, `revlib`, `photonvision`, `pathplanner`, `choreo`, `maplesim`), where it is load-bearing. The five `core.spi` interfaces all survive; ArchUnit rule 9 is unchanged. |

**The real win, stated plainly:** *"PumpkinLib code is replay-safe"* becomes **true unconditionally** instead of true-if-you-chose-the-right-backend. `PumpkinReplayVerify` (M20), the replay-safety lint (M20) and the cycle-counted health discipline (R3) all stop being hedged.

**The honest cost — and the maintainer knowingly traded the first of these away:**

1. **The "zero vendor `requires`, installable on kickoff morning before any vendor publishes" property is LOST, knowingly.** This was not a throwaway line. `README.md` advertised it prominently, `DESIGN.md` §2 carried it as **row 11 of the defensible core**, and it rested on a real, cited fact: **AdvantageKit's 2026 swerve templates shipped weeks late waiting on vendors.** That constraint has not gone away. **We have chosen to sit on the wrong side of it.** Every claim that depended on it is removed or rewritten:

   | Location | Old claim | Replacement |
   |---|---|---|
   | `README.md` §Modules | "Zero *vendor* `requires` … install PumpkinLib on kickoff morning before any vendor has published" | "PumpkinLib requires AdvantageKit. You cannot install PumpkinLib until AdvantageKit has published for the season. We took this trade deliberately, to make deterministic replay a guarantee rather than an option." |
   | `DESIGN.md` §2 defensible-core row 11 | "installs as one vendordep with zero *vendor* `requires`" | **Withdrawn from the defensible core.** It is no longer a differentiator; it is now a **disadvantage** relative to a WPILib-only library. |
   | `DESIGN.md` §5 artifact table | `pumpkinlib` depends on "WPILib only" | **WPILib + AdvantageKit.** The `requires[]` install-failure-multiplier argument still applies to the *vendor* adapters and still holds there. |
   | `DESIGN.md` §11 install path | "works on kickoff morning before any vendor has published" | "works as soon as AdvantageKit has published — which in 2026 was kickoff week, but is not guaranteed." |
   | `design/02-tuning.md` §"No AdvantageKit dependency" | listed as an advantage over 6328's `LoggedTunableNumber` | **Withdrawn.** We now have the same dependency they do. `TunableDouble`'s remaining advantages (single poller, FMS default-deny, `/applied` echo, 4-tier persistence) stand on their own. |

2. **Risk R18 is ACCEPTED, not mitigated.** It moves from **Medium-and-mitigated** to **High-and-accepted**: if AdvantageKit does not ship for WPILib 2027, **PumpkinLib does not ship.** The escape hatch that made it Medium was the `LogBackend` SPI, and it no longer exists. A bare accepted risk is not good enough for a dependency this load-bearing, so the contingency is decided **in advance** (`ROADMAP.md` §4.2):

   - **Trigger, armed:** at the WPILib 2027 **beta** (~Dec 2027), if AdvantageKit has no public 2027 branch or has publicly stated it will not port. Not at the alpha (too early to conclude anything); not at kickoff (too late to act).
   - **Tier 2 first — contribute.** Offer the port upstream *before* forking. A one-maintainer library forking another one-maintainer library over a platform migration is how small ecosystems fragment.
   - **Tier 1 — fork.** Publish `dev.pumpkinlib:pumpkinlib-akit-compat`: the minimum AdvantageKit surface PumpkinLib actually uses (`LoggedRobot`, `Logger`, `LogTable`, `LoggableInputs`, the WPILOG reader/writer, the replay driver), ported to `org.wpilib.*`, under our coordinates, with **a stated public intent to delete it the day upstream ships** and upstream credited prominently. **~2.5 pw, budgeted as a contingency line and NOT in the 74.0.** **`[UNVERIFIED]`: this tier is gated on the AdvantageKit license permitting redistribution and modification, which has not been checked.** If it does not, tier 1 does not exist.
   - **Tier 3 — wait, and say so first.** If neither works: **the 2027 line does not ship, and the README says so on its first screen** — not discovered in January by a team that already forked the template.

3. **Teams already on DogLog or plain Epilogue cannot adopt PumpkinLib without switching loggers.** This is a hard incompatibility, not a migration path, and it belongs in the adoption matrix as such:

   | A team currently running | Can adopt PumpkinLib? |
   |---|---|
   | AdvantageKit | **Yes.** The intended case. |
   | Nothing / `SmartDashboard` only | **Yes** — but they inherit AdvantageKit's IO-layer discipline and opinions along with it. A rookie team that wanted a tuning wizard now also gets a logging framework. That is a real pedagogical cost and it is not optional. |
   | **DogLog** | **No, not without switching.** DogLog is widely used precisely because it is cheap to adopt; asking a team to leave it for a tuning wizard is a large ask and some will decline. |
   | **Plain Epilogue** (WPILib first-party) | **No, not without switching.** This gets *worse over time*, because Epilogue is the first-party path and is where WPILib is investing. |

   **The honest summary: MD3 buys a guarantee and pays for it with reach.** The addressable population shrinks to AdvantageKit teams plus greenfield teams, and shrinks further every year Epilogue improves.

4. **`@AutoLog` / `@AutoLogOutput` were reconsidered and D24 STANDS.** D24 was made for **package-scope** reasons, not backend reasons, and making AdvantageKit required changes neither of its two premises: (a) a **vendordep cannot add an `annotationProcessor` line** to a consumer's `build.gradle`; (b) `@AutoLog` generates `XxxInputsAutoLogged` **in the same package as the annotated type**, so a library-owned annotated inputs class generates into `org.pumpkinlib.*`, where a team can neither usefully extend nor substitute it. **The package-scope concern is not resolved. D24 is kept for all library code.** What MD2 changes is orthogonal: `PumpkinTemplate`'s `build.gradle` is ours to write, so the template ships the `annotationProcessor` line pre-wired **for team code**. That is a template feature, not a library feature, and the distinction is the whole point of D24.

**Supersedes:** D13 (the `PumpkinRobot`/`PumpkinLoggedRobot` split). **Amends:** D9–D12 (the `LogBackend` SPI clause), D26 (`ServiceLoader` scope), D28 (dependency set), D29 (two shims → one), R10 (the "zero vendor `requires`" consequence).

---

## MD4. License — BSD-3-Clause.

**As stated by the maintainer, verbatim:**

> **License: BSD-3-Clause**, matching WPILib, so a team can vendor a single file with no legal question.

**Text:** [`LICENSE`](LICENSE), copyright line `Copyright (c) 2026 PumpkinLib contributors`. **Every "License: TBD" reference across the docs is superseded by this row** and by [`ROADMAP.md` §11](ROADMAP.md).

**Alternatives rejected:**

| Rejected | Why |
|---|---|
| **MIT** | Functionally similar, but it does not *match WPILib*. Matching matters here for a specific, practical reason: a team copying one file out of PumpkinLib into their robot project is already doing that with WPILib source, under a licence their mentors have already accepted. Same licence, same answer, no new question. |
| **Apache-2.0** | The patent grant and the NOTICE-file obligation are real value in a commercial context and pure friction in a high-school one. A rookie team should not have to reason about a NOTICE file to vendor a 40-line class. |
| **GPL / LGPL** | Copyleft on a robot codebase that teams routinely copy from each other is a hazard, not a protection, and it is incompatible with the vendoring behaviour the library explicitly wants to encourage (`docs/graduation.md`, `docs/removing-pumpkinlib.md`). |
| **Leaving it TBD until v0.1** | Under MD1 that is a **three-to-four-year** wait. R21 (maintainer continuity) makes the licence a *mitigation*, not a formality: if the maintainer stops, a permissive licence plus Maven Central mirroring is what lets someone else continue. Deciding it late would have meant carrying the project's largest continuity risk unmitigated for its entire build. |

**Rationale, as given:** a team must be able to vendor a single file with no legal question, and anyone must be able to fork if the project stalls.

**The honest cost:** essentially none in the ordinary case — but it is worth naming the two things BSD-3-Clause does *not* do. It gives **no patent grant** (Apache-2.0 would), and it gives **no protection against a vendor shipping a closed derivative** of the tuning wizard. Both were judged acceptable: this is high-school robotics tooling, and the *goal* of R21's mitigation is precisely that someone else can take the code and run.

**Load-bearing for:** R21 (maintainer continuity, High) and the M24 Maven Central mirroring item, which is the technical form of the same succession plan.

---

## Part 0.5 — Supersession index

Every prior row these four decisions touch, in document order. The originals are all left standing.

| Row | Status | Reason |
|---|---|---|
| **A4(e)** — "a vendordep cannot add an `annotationProcessor` line" | **AMENDED by MD2** | True of the vendordep, false of the template. M20's replay-safety lint is deliverable *because* the template can add the line. |
| **A5** — vendor parity in sim is release gate **G1** | **AMENDED by MD1** | G0–G5 are deleted. Vendor parity is now the **M3 gate**; sim-to-hardware current-spike transfer is an **M5** item. The gate itself is unchanged and is now **non-cuttable** — there is no dated release to protect, so M3 simply does not complete until both vendors pass. |
| **D9–D12** — "Telemetry owns logging (`PumpkinLog` static, `LogBackend` SPI)" | **AMENDED by MD3** | Telemetry still owns logging. The **`LogBackend` SPI is deleted**; `PumpkinLog` writes to `Logger` directly. Alerts and tunables ownership unchanged; the `Pumpkin` god-object stays deleted. |
| **D13** — `PumpkinRobot extends TimedRobot` in core + `PumpkinLoggedRobot` in the AdvantageKit adapter | **SUPERSEDED by MD3** | AdvantageKit is required, so there is one class: `PumpkinRobot extends LoggedRobot`. |
| **D24** — PumpkinLib uses neither `@AutoLog` nor `@AutoLogOutput` | **REAFFIRMED under MD3** | Reconsidered explicitly. Both premises are package-scope, not backend-scope, and neither is resolved. **Stands.** |
| **D26** — `core.spi` + `ServiceLoader.load(LifecycleHook.class)` for every hook | **AMENDED by MD3** | The five interfaces and ArchUnit rule 9 survive unchanged. `ServiceLoader` narrows to **out-of-jar adapters only**; in-jar hooks become an explicit priority-ordered list built in `PumpkinLifecycle.create()`. |
| **D28** — three code artifacts, `pumpkinlib` on "WPILib only", revisit the split "in v0.3" | **AMENDED by MD1 + MD3** | `pumpkinlib` now depends on **WPILib + AdvantageKit**; `pumpkinlib-advantagekit` and `pumpkinlib-doglog` are deleted. There **is no v0.3** — the split is revisited only if a consumer asks after v0.1. `pumpkinlib-gradle` becomes load-bearing for onboarding under MD2, not just for `pumpkinCheckDeploy`. |
| **D29** — `PumpkinLifecycle` public; `PumpkinRobot` **and** `PumpkinLoggedRobot` as shims | **AMENDED by MD3** | One shim, not two. The public-lifecycle decision itself **survives intact and is the reason MD3 collapses cleanly** — teams that wire manually are unaffected by the base-class change. |
| **R6** — the fault-injection test is a **G2** gate item | **AMENDED by MD1** | Gate renaming only. It is now the **M7 gate**, and M7 is unshippable without it. |
| **R8** — support policy, bug bundle, kill switch, rip-out guide, all "in v0.1"; gate **G4** | **AMENDED by MD1 + MD2** | All four deliverables move **earlier**, into **M8**, because that is when 8793 and 9143 start consuming snapshot artifacts. The G4 second-committer clause becomes the **M24** gate. The support policy's *audience* changes: there are no strangers before M24, so its first real users are the maintainer's own teams — which is the correct order, because a safety valve nobody has ever pulled is not a safety valve. `pumpkin doctor --bundle` gains the MD2 template-drift table. |
| **R9** — "v0.1 is ordered by match-record leverage, and the wizard is in it"; drive/vision/auto to v0.2 | **SUPERSEDED as a release plan by MD1; the ordering survives** | Nothing moves to a later release, because there are no later releases. The *reasoning* — order by match-record leverage, put the capability with no ecosystem substitute early — is preserved exactly, as build order: platform → units → seam → mechanisms → sim → tunables → **wizard (M7)**, and only then drive (M9), vision (M10) and auto (M11). |
| **R10** — "The First Session"; the "zero *vendor* `requires`" rule | **AMENDED by MD2 + MD3** | The two-hour honesty and the Command-Robot-template correction stand. The "zero *vendor* `requires`" rule is **withdrawn entirely** (MD3). `pumpkin init` as step 1 is strengthened by MD2 — it now produces a whole project, not a wiring session. |
| **R11** — honest numbers, "computed against the v0.1 scope only", "the v0.3 dependency named" | **AMENDED by MD1** | The measurement is unchanged (~132 lines / 4 files vs ~1,620 / 14, ~12× at ~3.5× fewer files, with the same six exclusions named in both columns) but its **denominator must be restated against the M1–M11 internal build**, not against "v0.1", because v0.1 now means everything. Every "v0.2" / "v0.3" qualifier in that row is dead text and must be rewritten as a milestone reference. |
| **X5** — the `Superstructure` router "stays v0.2" | **SUPERSEDED as a version placement by MD1; the ordering survives** | The router is **M14**, after interlocks in M4. The reason is unchanged and still good: interlocks are 80% of the safety value at 20% of the cost, and the router's API must be validated against 9143-A's real CorAl geometry (R11) before it freezes. |
| **X6** — cut `PhotonCameraIO` and the `AutoStep` DSL to fund the wizard | **SUPERSEDED by MD1** | Nothing is cut by domain. `PhotonCameraIO` is M16, the `AutoStep` DSL is M11 with **both** PathPlanner and Choreo. The cut list C1–C5 is replaced by the depth levers L1–L19, of which L14 (Choreo degrades to load-and-run) and L6 (`CameraArbiter` → first-valid-wins) are the nearest survivors — and they are levers to be fired under pressure, not the plan. |
| Every **"License: TBD"** reference | **SUPERSEDED by MD4** | BSD-3-Clause. Text in [`LICENSE`](LICENSE). |

---

# Part 1 — The five decisions everything else hangs off

### A1. The hardware seam is a *goal in mechanism units*, never a voltage

**Chosen:** `MotorIO.setPositionGoal(double outputRotations, double rps, double arbFfVolts)`, plus an optional per-call constraint override.

**Rejected:** `setVoltage(double)` with a roboRIO-side PID at 50 Hz — the seam every previous FRC vendor-neutrality attempt chose.

**Why:** a voltage seam throws away Motion Magic, FOC, `SensorToMechanismRatio`, `refreshAll` batching, setpoint latching, fused CANcoders, and REVLib 2026's on-controller `FeedForwardConfig`. That is essentially everything a Kraken costs more than a CIM for. The public community critique of monolithic motor wrappers is answered *structurally* rather than rhetorically: `ControlLocation` is an explicit, logged, alert-checked field, and a backend that cannot honor the request downgrades in English.

**The `rps` parameter is not optional.** Revision 1 had `setPositionGoal(pos, arbFf)`, which makes a field-locked turret — position goal plus a nonzero velocity feedforward from chassis omega — unimplementable.

---

### A2. One `Reduction`, one `Axis`, one `MechanismUnits` — applied exactly once each

**Chosen:** the gearbox is applied once, inside the vendor config (`SensorToMechanismRatio`). The geometry is applied once, in `MechanismUnits`. Nothing in team Java ever multiplies by a ratio. `Reduction` is **positive-only**; direction lives on an invert flag.

**Rejected:** allowing a negative ratio to express direction, which is what the user's own repos do today.

**Why:** this single decision deletes 41 inline `/360.0` conversions, the `TURRET_ROTATOR_GEAR_RATIO = -20/200.0` sign-cancellation family and its seven downstream sites, and the double-applied-ratio seeding bug — all live in `9143-2025-A-Updated` and `reefscape2025` today. A tuner that fits a negative kV hands a student a physically meaningless number. Positive-only makes the sign hack *unrepresentable*, not merely discouraged.

**Rejected sub-decision:** a `Reduction.calibrationScale` for belt stretch. It is a place to hide a wrong ratio, and the failure it would mask is exactly the one `describe()` exists to expose.

---

### A3. `Gains` is seven doubles, volts-per-SI, converted once at a `GainSink`

**Chosen:** `Gains(kP, kI, kD, kS, kV, kA, kG)`, all SI, built by `Gains.pid(p,i,d).withKs(..).withKv(..).withKa(..).withKg(..)` — **named fields only, no multi-double constructor.** Gravity *mode*, motion constraints, tolerance, neutral mode and manual-control parameters all live on `ControlConfig`.

**Rejected (a):** vendor-native gains. The same steer motor's kP is ~7 on the roboRIO, ~100 in Phoenix, ~0.01 in REVLib. A 10,000× spread makes gains untransferable between vendors, between teams, and between a WPILib tutorial and a real robot. Canonicalizing is the load-bearing precondition for the entire tuning domain.

**Rejected (b):** a *rich* `Gains` carrying gravity mode, profile constraints and tolerance (which is what revision 1's D1 specified, and which the review's D1a recommendation would have kept). A tuner writes gains; it does not write policy. Mixing them means `TunedValueStore`, `ValueExporter`, the NT schema and the wizard all have to understand mechanism configuration. Seven measurable physical quantities is the right object.

**Rejected (c):** a positional multi-double constructor. `reefscape2025/util/custom/GainConstants.java` has a **live** positional-overload bug where `(P,I,D,FF,minOut,maxOut)` silently binds to `(P,I,D,S,V,G)`, dropping the feedforward. Named-field-only construction makes that class of bug unrepresentable.

---

### A4. Config is data; validation errors are *values*, not exceptions

**Chosen:** immutable records + fluent builders + `with*()` copies. Compact constructors are **pure, local and non-throwing**, and store a `List<ConfigError>`. `PumpkinRegistry.addAll(...)` collects every config's errors, runs the cross-config checks (CAN IDs, setpoint names, bus budget), prints **all of them at once**, and enters `SAFE_MODE`. **The robot boots either way.**

**Rejected (a):** flat `public static final` constants. Cannot express two of the same mechanism, cannot be passed as a value, cannot be validated. The 9143 A/B sibling robots and the practice-bot case make this disqualifying.

**Rejected (b):** deploy-directory JSON, YAGSL-style. YAGSL's own docs say configuring a module "requires a lot of patience and you will likely never get it working on the first try," and that a wrong value produces "behavior you won't easily be able to identify." Invisible to code review; does not round-trip into replay.

**Rejected (c):** mutable config embedding the vendor config (254's `ServoMotorSubsystemConfig`). Excellent for a single-vendor team, but it *is* a `TalonFXConfiguration`, so it cannot serve REV or generic hardware and has no home for validation. We keep its negative-space lesson: model only what is physical, shared, or derivable; everything else goes through `applyRaw()`.

**Rejected (d), and this is the revision-2 correction:** **throwing from the compact constructor.** Every example declares configs as `public static final` fields. A throw there is `ExceptionInInitializerError` from `frc.robot.RobotConfig.<clinit>`: robot code never starts, the driver station shows red "Robot Code," and the beautifully written `ConfigError` message is a *nested cause* under stack frames that are all JVM class-init machinery. Revision 1 did exactly this **and separately claimed such errors are "caught before `robotInit` finishes… never on the field."** A student editing a soft limit at an event hits it with a dead robot and no obvious message.

**Rejected (e):** our own annotation processor. A vendordep **cannot** add an `annotationProcessor` line to a consumer's `build.gradle`. Same reason `@AutoLog` is banned library-wide.

> **⚠ A4(e) AMENDED by MD2 (2026-08-07).** The premise is still true of a **vendordep**, and D24 still stands for library code. But under MD2 the **template's `build.gradle` is ours to write**, so an annotation processor *is* deliverable to team code — and M20's `pumpkinlib-lint` javac processor exists **only** for that reason. The rule is therefore restated: **PumpkinLib does not require an annotation processor to function, and never generates into `org.pumpkinlib.*`; the template may wire one for team code, opt-out in one line.** Depth lever L17 (−1.4) drops the processor and ships the runtime tripwire only, if it ever comes to that.

---

### A5. Sim-first, with no second code path

**Chosen:** declaring mass or MOI is the *only* thing a team writes to get physics. Phoenix and REV simulate through their own vendor sim state (`TalonFXSimState`, `SparkMaxSim`), so simulation exercises the real ratio path. Vendor parity in simulation is a **gate**, not a goal — **the M3 gate** since MD1 deleted the dated gates G0–G5, and now non-cuttable, because there is no dated release to protect.

**Rejected:** a separate `*IOSim` class per mechanism, which is the AdvantageKit template shape and what all three of the user's repos do.

**Why:** a unit-conversion mistake shows up in `simulateJava`, not on the field. The NEO path in the user's own template has **no simulation at all**, so "switch one constant to NEO" ships untested code today. And simulation is the one practice deficit software can partially substitute for: a mechanism debugged at a desk is a Saturday not burned.

> **⚠ A5 AMENDED by MD1 (2026-08-07) — gate renaming only.** G0–G5 are deleted. Vendor parity in sim is the **M3 gate condition**; the sim-to-hardware transfer of the current-spike homing threshold is an **M5** item. The decision is unchanged and is now **stronger**: with no dated release to protect, parity is **non-cuttable** — contingency C1 (ship Phoenix-only) is deleted along with the rest of the cut list, and M3 simply does not complete until both vendors pass.

---

# Part 2 — Integration decisions (who owns what)

Twenty-nine type/concept collisions across six independently written domain designs. Full table in [`DESIGN.md` §5](DESIGN.md#5-integration-decisions--conflicts-reconciled); the ones with non-obvious reasoning are here.

### D8. `TuningTarget` lives in **core**, not in the tuning package

**Chosen:** `org.pumpkinlib.control.TuningTarget`, together with `MechanismArchetype`, `TravelLimits`, `PlantPrior`, `GainSink`, `Controllers`, `SafetyEnvelope` and `TuningSupervisor`.

**Rejected:** leaving it in `org.pumpkinlib.tuning`, which is where its author put it.

**Why:** it would invert the dependency — the mechanism layer would depend on the tuning layer. More importantly, this is **the single most important incremental-adoption seam in the library**. A team with hand-rolled subsystems implements `TuningTarget` in ~30 lines and gets the wizard without adopting the mechanism layer *or* the config system. `docs/graduation.md` leads with it.

*(Unaffected by MD1–MD4, and made more important by MD1: with no release for three years, the seam that lets 8793 and 9143 use the wizard against their existing subsystems is the whole value line for M7.)*

---

### D9–D12. One facade per concern, and the god-object is deleted

> **⚠ AMENDED by MD3 (2026-08-07).** Ownership is unchanged. The **`LogBackend` SPI is deleted** — `PumpkinLog`'s tiered facade writes to AdvantageKit's `Logger` directly, and `Nt4LogBackend` / `EpilogueLogBackend` / `DogLogLogBackend` / `AdvantageKitLogBackend` do not exist. Alerts, tunables and the deleted god-object are untouched.

Five domains each independently proposed a logging facade, five proposed an alert facade, five proposed a tunable type. **Telemetry owns logging** (`PumpkinLog` static, `LogBackend` SPI). **Platform owns alerts** (`Alerts` / `PumpkinAlert` / `AlertRegistry`). **Tuning owns tunables** (`TuningRegistry`).

`org.pumpkinlib.Pumpkin` — a proposed global entry point exposing `TUNING_MODE`, `telemetry()`, `alerts()`, `dt()` and `registry()` — is **deleted**. It couples all six domains through one class. Its members redistribute to their owners.

---

### D13. `PumpkinRobot` vs `LoggedRobot` — the split

> **⛔ SUPERSEDED by MD3 (2026-08-07).** AdvantageKit is a required dependency, so there is nothing to split around. **One class: `org.pumpkinlib.core.PumpkinRobot extends LoggedRobot`**, still a ~20-line delegating shim over the public `PumpkinLifecycle` (D29, which survives intact). `PumpkinLoggedRobot`, the `LogBackend` SPI and the `pumpkinlib-advantagekit` artifact do not exist.

**Original (revision 1, kept for the reasoning history):** `PumpkinRobot extends TimedRobot` lives in core; `PumpkinLoggedRobot extends LoggedRobot` lives in the AdvantageKit adapter; **core must not depend on AdvantageKit**, so that a team using NT4, Epilogue or DogLog pays nothing for a logging framework they did not choose, and so that PumpkinLib installs with zero vendor `requires`.

**Why it was superseded:** the property it protected — *no forced logging framework, installable before any vendor publishes* — was real and is now deliberately given up (MD3, cost 1). What it cost was that deterministic replay, the property every honesty claim in the library is checked against, was conditional on a runtime choice. The maintainer traded reach for a guarantee, with both sides of the trade written down.

---

### D21. The superstructure command declares the **union** requirement

**Chosen:** `Superstructure.request(G goal)` returns `Commands.run(() -> planner.step(goal), requirementsArray())` where `requirementsArray()` is `{this, ELEVATOR, ARM, SHOOTER, ROLLER}` — every coordinated mechanism, every time. The mechanisms **stay registered with the scheduler** so their `periodic()` still runs.

**Rejected:** requiring only the `Superstructure`, having the coordinated mechanisms not self-register, and logging a named warning if a direct factory is used anyway. **This is what revision 1 specified, in two consecutive and mutually contradictory sentences.**

**Why:** if the returned command requires only the `Superstructure`, then `ELEVATOR.goTo("L4")` and `m_super.request(SuperState.INTAKE)` share *no* requirement. The scheduler cannot detect the conflict. Both run. Both write a goal to the same `PositionMechanism` every loop, and the last writer wins nondeterministically by registration order. Demoting that to a log line turns an actuator-ownership violation into a warning nobody reads. Requirements are the WPILib mechanism for exactly this problem, and declaring the union means the two commands correctly interrupt each other — which is the desired semantics anyway.

Requirement conflicts and periodic registration are independent in WPILib, so keeping the mechanisms registered costs nothing.

---

### D26. `org.pumpkinlib.core.spi` — because the runtime loop did not compile

> **⚠ AMENDED by MD3 (2026-08-07).** The diagnosis, the package, the five interfaces and ArchUnit rule 9 are all **unchanged and still load-bearing**. What changes is the *discovery mechanism*: D28 had already put telemetry, tuning, sim and vision in one jar, so the compile cycle this row exists to break is now broken by package structure alone. `PumpkinLifecycle.create()` builds an **explicit priority-ordered hook list in code**; `ServiceLoader` survives **only** for genuinely out-of-jar adapters (`phoenix6`, `revlib`, `photonvision`, `pathplanner`, `choreo`, `maplesim`), where it is load-bearing. This is most of the −1.25 pw MD3 saves, and it also deletes a class of "the hook silently did not load because the `META-INF/services` entry was dropped by a shadow jar" bug.

**Chosen:** one new package in core containing `LifecycleHook`, `VisionSimHook`, `MechanismGeometrySink`, `MechanismGeometry` and `SimMotorHandle`. `PumpkinLifecycle` iterates `ServiceLoader.load(LifecycleHook.class)` sorted by `priority()`. Telemetry, tuning, sim and vision each ship a `META-INF/services` entry. **Every dependency arrow points into core**, enforced by ArchUnit rule 9.

**Rejected:** revision 1's arrangement, in which `PumpkinRobot` (core) called `PumpkinLog.beforeUserPeriodic()` (telemetry) and `TuningRegistry.periodic()` (tuning) while the artifact table had telemetry and tuning depending on core. **As drawn it does not compile.** The same cycle appeared for `PumpkinRegistry → PumpkinSim` and for `PumpkinSim → PumpkinVisionSim`, where the shared type had no declared home at all.

**Related:** `MotorIO` gains `default Optional<SimMotorHandle> simHandle() { return Optional.empty(); }`. `TalonFXMotorIO` (in the Phoenix adapter) implements it by constructing a `TalonFXSimHandle` — **the CTRE import stays inside the adapter, which is legal.** `PumpkinSim` consumes the handle and never names a vendor type. Revision 1 had no legal dependency edge for a Phoenix sim handle to exist anywhere.

---

### D27. One `PumpkinRegistry.addAll(Object...)`, routing by `instanceof`

**Chosen:** one call inspects each argument once and routes it — `TelemetrySource` → telemetry, `HealthSource` → `HealthMonitor`, `SelfTestable` → `SelfTest`, `TuningTarget` → `TuningRegistry`, `Subsystem` → optional scheduler registration. Opt-**out** filters on the config (`.excludeFrom(Registry.TUNING)`). A one-line boot summary prints what was routed.

**Rejected:** four parallel registration lists — `PumpkinRegistry.addAll`, `SelfTest.registerAll`, `TuningRegistry.registerAll`, `HealthMonitor.watch` — which is what revision 1 showed **in the example whose stated headline was "ONE list."** The membership differed between lists for non-obvious reasons. Adding a fifth mechanism and forgetting one fails silently: no telemetry, or no self-test, or no tunables. That is precisely the bug class this library exists to delete, reintroduced in the flagship example.

---

### D28. Three code artifacts, not eleven

> **⚠ AMENDED by MD1 + MD3 (2026-08-07).** Three consequences. **(1)** `pumpkinlib` no longer depends on "WPILib only" — it depends on **WPILib + AdvantageKit**, and `pumpkinlib-advantagekit` and `pumpkinlib-doglog` are deleted (MD3). **(2)** "Revisit in v0.3" is dead text: **there is no v0.3.** The split is revisited only if a real consumer asks to install a subset, after v0.1. **(3)** `pumpkinlib-gradle` is promoted from a build-check plugin to **load-bearing onboarding infrastructure** (MD2) — it is what `PumpkinTemplate` applies, and `pumpkinCheckDeploy` becomes a gate a forked template runs on every deploy. The core reasoning — one consumer-visible unit, package boundaries kept so the split stays available at zero cost — is unchanged and is *reinforced* by MD2, since a template pinning eleven coordinates would be eleven chances for a version-set mismatch in a team's fork.

**Chosen:** `pumpkinlib` (one jar; WPILib + AdvantageKit after maintainer decision 3 — the `pumpkinlib-advantagekit` and `pumpkinlib-doglog` adapters are deleted), `pumpkinlib-phoenix6`, `pumpkinlib-revlib`, plus `pumpkinlib-gradle` and the per-vendor adapters that genuinely carry a vendor dependency. **Package and source-set boundaries stay exactly as designed**, and all twelve ArchUnit rules keep enforcing them at the package level, so the multi-artifact split remains available later at zero cost.

**Rejected:** eleven separately published core artifacts (`-pure`, `-core`, `-telemetry`, `-mechanism`, `-tuning`, `-sim`, `-vision`, `-drive`, `-auto`, `-elastic`, `-testkit`), all shipping behind a single `PumpkinLib.json` and therefore always installed together.

**Why:** no consumer could ever install a subset, so the split delivered **zero consumer-visible benefit** — against eleven POMs, eleven version bumps, eleven inter-artifact version constraints, and eleven chances for a botched publish, on every release, several of which go out during build season. Precedent, cited honestly: **WPILib itself ships as one vendordep-visible unit and splits internally.** ~~Revisit in v0.3 if a consumer actually asks.~~

**Also:** revision 1's §7.4 depended on "the task class in pumpkinlib's gradle plugin jar" — a twelfth published artifact that appeared in no table anywhere. It is now `pumpkinlib-gradle`, with coordinates and a Plugin Portal ID.

---

### D29. `PumpkinLifecycle` is **public**, and manual wiring is documented first

> **⚠ AMENDED by MD3 (2026-08-07) — one shim, not two.** The decision itself **survives intact and is the reason MD3 collapses cleanly**: because the lifecycle is public and manual wiring is the documented-first path, changing `PumpkinRobot`'s base class breaks nobody who wired by hand. The four CI fixture projects are unchanged, and `IncrementalAdoptionTest` still asserts that only `full` references `PumpkinRobot`. Under MD1 they move into **M8**, and under MD2 the `mechanism-only` template variant makes this path a shipped product rather than a fixture.

**Chosen:** `public static PumpkinLifecycle create(LogConfig)`, `robotInit()`, `beforeUserPeriodic()`, `afterUserPeriodic()`, `disabledInit()`, `close()`. ~~`PumpkinRobot` and `PumpkinLoggedRobot` become ~20-line delegating shims.~~ **Amended by maintainer decision 3:** there is one shim, `PumpkinRobot extends LoggedRobot`; `PumpkinLoggedRobot` does not exist. The rest of D29 — a public lifecycle with manual wiring documented first — is unchanged and is the reason decision 3 collapses cleanly. Four CI fixture projects (`tunables-only`, `one-mechanism-only`, `health-only`, `full`) compile on every PR; only the last may reference `PumpkinRobot`.

**Rejected:** package-private `PumpkinLifecycle` with `extends PumpkinRobot` as the only path — revision 1's D13.

**Why:** this was the largest credibility gap in revision 1. Principle "a team can delete PumpkinLib from one subsystem mid-season without touching the others" was asserted in §1 and §13 and **demonstrated nowhere**, while the only object that would make partial adoption possible was deliberately hidden. Every one of the user's three FRC repos — 8793 with an existing `CommandSwerveDrivetrain`, 9143 with `LoggedRobot` — would have had to change its base class to adopt anything at all. A promise with no fixture is marketing.

---

### D30. `ControlMap` has modes, and one of them must be `MANUAL`

**Chosen:** `mode(String, Consumer<ControlMap>)` / `inMode(String)` / `modeSelector(Trigger)`. Every binding inside a `mode` block is automatically ANDed with `inMode(name)`. `publish()` raises a persistent alert if a `ControlMap` declares modes and none is `MANUAL`. The active mode is published to `/Pumpkin/Driver/Mode`. Cost: 0.2 pw — it is a `Trigger.and()` wrapper.

**Rejected:** modeless bindings with `abortWhen` on the automated commands, which is what revision 1's flagship showed.

**Why:** `abortWhen` returns the driver to *nothing*. From the research dossier: *"Automation without a manual mode loses matches. A single-button macro that depends on vision will fail when a tag is occluded by a defender, and if there is no fallback the robot is dead for the match."* 254 gates every binding through `ModalControls.modeSpecific()` with three modes including `CORALMANUAL`; 1678 keeps a dedicated debug controller. The fallback has to be a button, not a rebuild.

*(Under MD2, the shipped `PumpkinTemplate` `RobotContainer.java` declares a `MANUAL` mode out of the box, so the first thing a team sees is the correct shape.)*

---

# Part 3 — Decisions made in response to the adversarial review

### R1. `Gains.UNTUNED` is a real sentinel with defined behaviour, and quickstarts ship it

**Chosen:** `Gains.UNTUNED` (`kP = NaN`). In **simulation** it resolves at mechanism construction to a physics-derived first guess from `PlantPrior`, the demo moves, and the boot dump says *"derived from your declared mass, not measured — run the tuning wizard."* On **real hardware** the mechanism **refuses closed-loop control**: `goTo()` raises a `kError` alert and holds neutral. Homing and manual control still work. Numeric literals move to a labelled *"a tuned elevator, for reference — 8793's numbers for 8793's hardware"* block.

**Rejected:** revision 1's quickstart, which shipped `kP 6.0, kD 0.1, kS 0.15, kV 0.62, kA 0.03, kG 0.32` as pasteable literals and told a rookie the only thinking required was *"edit four numbers: the two CAN IDs, the gear stages, the sprocket."*

**Why:** a team that pastes that, edits four numbers, and deploys is running another team's elevator gains on their hardware at `ON_MOTOR_PROFILED`, on a Kraken, at full authority. Tier-3 placeholder detection only fires on sentinels, and that block deliberately avoided them, so nothing warned. This was the single most likely way PumpkinLib breaks a rookie's first mechanism.

*(MD2 raises the stakes: the template ships a worked elevator config to **every** adopting team, so `Gains.UNTUNED` in the template is not a documentation choice, it is the safety property. The template's `gains.json` ships empty and schema-stamped for exactly this reason.)*

---

### R2. The flagship gear ratio, and a Tier-1 rule that makes the class of error unrepresentable

**Chosen:** the arm is `Reduction.ofTeeth(58, 10).then(58, 18).then(42, 12)` = **65.411:1**, with `FusedCancoder.rotorPerSensor = 65.411`. And a hard Tier-1 `Validation` rule: for `FusedCancoder`/`RemoteCancoder`, `rotorPerSensor × sensorPerOutput` must equal `reduction.rotorPerOutput()` within 1%, or emit a `FATAL` `ConfigError` naming both numbers, both fields, and the identity.

**Rejected:** the literal annotation `// = 34.126:1` with `rotorPerSensor = 34.126`, which is what revision 1 printed in three places.

**Why:** 58/10 × 58/18 × 42/12 = 65.411, not 34.126. Phoenix requires `RotorToSensorRatio × SensorToMechanismRatio == rotor-per-output`, so the fused arm was mis-scaled by **1.92×** — in the very example used to demonstrate "change the gear ratio in exactly one place," and nothing in `Validation` caught it. Correcting the literal is necessary; **adding the rule is the actual fix**, because a corrected literal is one review away from drifting again.

*(`design/01` §5.5 and §9.1 still print 34.126 and are listed as an outstanding correction in `DESIGN.md` §16. The rule itself is an **M2 gate item**.)*

---

### R3. Health polls **one slice per loop**, cycle-counted — not 4 Hz, not 50 Hz

**Chosen:** `SliceScheduler` runs exactly one registered `HealthSource` per loop, round-robin, indexed by `Clock.periodCycles`. The default eleven-slice registration sweeps in 220 ms. `LoopTimeMonitor` is not a slice — it measures loops, so it runs every loop, at ~30 µs.

**Rejected (a):** 50 Hz fault polling. Team 135 measured full fault checking at ~10 ms/loop — over half a 20 ms budget.

**Rejected (b):** 4 Hz polling, which is what revision 1 specified and defended in its own decisions table. Polling everything at 4 Hz **does not make the work cheaper, it makes it bursty**: a 10 ms spike every 250 ms overruns every twelfth loop. And a wall-clock gate is **nondeterministic under replay**, which silently breaks the property the telemetry domain exists to protect.

*(**Strengthened by MD3.** The replay argument in (b) was previously conditional — it only bit if the team chose the AdvantageKit backend. Replay is now a guaranteed library property, so a wall-clock gate anywhere in PumpkinLib is an unconditional defect, and M20's replay-safety lint can flag it at build time rather than argue about it.)*

---

### R4. NetworkTables tunables are 100–150 ms, and there is exactly one poller

**Chosen:** `TuningRegistry` holds **one** `NetworkTableListenerPoller` subscribed to the `/Tuning` prefix with `EventFlags.kValueAll`; `periodic()` calls `readQueue()` once and dispatches by topic name. `GainSink` publishes `/Tuning/<ns>/<key>/applied` after a successful apply. Every document states the latency as **about 100–150 ms**.

**Rejected (a):** revision 1's claim that "the gain is pushed to the simulated controller on the next loop… Overshoot appears on the graph in real time." AdvantageScope and Elastic are NT *clients*; the robot is the server; a client publisher's default update period is 100 ms unless it sets `PubSubOption.periodic` or explicitly flushes. Intermediate slider values are coalesced away. Under principle 12 this was a release-blocking documentation defect.

**Rejected (b):** per-tunable `DoubleSubscriber.get()` polling. On the §10B example robot that is **~250 JNI calls per loop** whenever tuning is enabled — i.e. the entire practice field. One queue drain is one JNI call regardless of tunable count, and it does not lose intermediate values.

---

### R5. Gains are written with `applyFast`, not `applyVerified`

**Chosen:** `TalonFXMotorIO` writes `Slot0Configs` through `Phoenix6GainSink` using `apply(cfg, 0.0)` (non-blocking), rate-limited to 10 Hz, and the limiter is part of the `MotorIO` contract rather than only of `TunableGains`. `applyVerified` (blocking) is used at construction, in disabled, and in self-test.

**Rejected:** revision 1's `MotorIO.applyGains(Gains)` documented as "safe to call every loop," implemented with `getConfigurator().apply(...)`.

**Why:** `TalonFX.getConfigurator().apply(Slot0Configs)` is a **blocking CAN transaction with a 50 ms default timeout**. Revision 1 called five of them from `periodic()` at 10 Hz. Doc 01 said "every loop" and doc 02 said "rate-limited to 10 Hz," and nothing reconciled them.

**Related:** `TalonFXMotorIO` never writes `Slot0Configs` directly. It delegates to `Phoenix6GainSink.apply(Gains)`, which is the only code in the library that touches `Slot0`. Revision 1 had doc 02 §4.2 specifying a conversion table and doc 01 §3.5 writing `s.kP = g.kP()` **raw**, with no conversion at all.

---

### R6. `arm()` throws outside Test mode; twelve abort conditions; a fault-injection test as a gate

> **⚠ AMENDED by MD1 (2026-08-07) — gate renaming only.** The fault-injection test is no longer "a G2 gate item"; it is **the M7 gate**, and M7 does not complete without it. Under MD1's never-reduce list, the twelve aborts and the fault-injection test are explicitly non-negotiable **at any capacity**, including at student-team capacity where M7 is off-limits to students entirely.

**Chosen:** the wizard's safety argument is **Test mode**, not FMS-not-attached. `TuningSupervisor` carries twelve abort conditions including `OVERTEMP` (80 °C stator) and `CONFIG_REJECTED` (a `GainSink.apply()` returning false mid-refinement), plus a >11.5 V battery precondition at `arm()`. A **required fault-injection test** — a stuck mechanism, a 10× runaway, a frozen encoder — asserts each trips the correct `AbortReason` within 3 loops and that `stop()` was called. That test is **the M7 gate** (MD1 deleted the dated gates G0–G5), and MD1's never-reduce list makes it non-negotiable at any capacity.

**Rejected (a):** gating on FMS-not-attached. That is true in the shop, in the pit, and at 11 p.m. with a student leaning over the arm.

**Rejected (b):** continuing a refinement loop after a rejected config. Revision 1's loop did `apply(g)`, ran a step, and analyzed the response — but if the device rejected the config, the response was produced by the **old** gains and the analyzer attributes it to the new ones, driving the update rules in a random direction.

**Rejected (c):** shipping the supervisor with no test proving it stops anything. A gate with no failing test is a gate nobody has checked.

---

### R7. Two vision structs, pre-warmed at boot; `VisionFrame` is not one

**Chosen:** exactly two custom structs exist in the whole library, both in vision, both provably fixed-size: `VisionFrameHeader` and `TargetObservation` (scalars, a `Rotation2d`, a `Transform3d`, and a fixed-length `double cornerTxRad[4]`, which WPILib's struct schema supports). Both are **registered and written once during `robotInit()`** so AdvantageKit's documented >100 ms first-log cost lands at boot rather than at match start. `hasBest`/`hasAlt` flags with zeroed transforms replace nullable fields, so `pack()` cannot NPE on the first object-detection frame. **`VisionFrame` is not `StructSerializable`** — `Optional<Pose3d>`, `int[] tagIds` and `List<TargetObservation>` have no fixed `getSize()`.

**Rejected (a):** revision 1's `VisionFrame implements StructSerializable` with a `VisionFrameStruct`. That is simply impossible, and the same document had already stated the rule and solved it correctly for its own wire format.

**Rejected (b):** the reviewer's recommendation to delete **both** structs and log flat parallel arrays per camera. See X3 below — this is a rejected critique.

*(**MD3 makes the pre-warm mandatory rather than defensive.** AdvantageKit's >100 ms first-log cost was previously one backend's problem; it is now everyone's, unconditionally. The `robotInit()` pre-warm is an **M10 gate item**.)*

---

### R8. Support policy, bug bundle, kill switch, rip-out guide — all in v0.1

> **⚠ AMENDED by MD1 + MD2 (2026-08-07).** All four deliverables **move earlier**, to **M8**, not later — M8 is when 8793 and 9143 begin consuming snapshot artifacts, and a kill switch that has never been pulled is not a kill switch. The G4 clause (*a second person has push access and has cut one release*) becomes an **M24 release gate**. Two changes of substance: **(1)** the support policy has **no strangers to serve before M24**, because nothing is announced and no public vendordep URL exists — so its first real users are the maintainer's own teams, which is the correct order and is how the rip-out procedure gets exercised for real before anyone else depends on it; **(2)** under MD2, `pumpkin doctor --bundle` also carries the **template-drift table**, because "which template-owned files did you edit" is otherwise undiagnosable over a forum thread.

**Chosen:** four concrete deliverables (full text in [`DESIGN.md` §13 R15](DESIGN.md#13-risks--mitigations)): a stated in-season support policy in the README above the install instructions; `pumpkin doctor --bundle`; a runtime kill switch readable from `src/main/deploy/pumpkin/disabled.txt`; and `docs/removing-pumpkinlib.md` with a `RipOutTest` CI fixture. The **M24** release gate adds *"a second person has push access and has cut one release"* (MD1 deleted the dated gates G0–G5; G4 → M24).

**Rejected:** revision 1's R15, which addressed only the *abandonment* scenario (Maven Central mirroring) and rated the residual as "One maintainer running three teams. This does not go away."

**Why:** abandonment is not the scenario that kills adoption. **Week 4 of build season, a team hits a bug, and the author is at a regional with 8793** is. Revision 1 had no stated support policy, no triage cadence, no distinction between supported and best-effort surface, no bug-report bundle, no runtime way for a stuck team to disable one subsystem and keep driving, and no named second committer.

**MD1 postscript, and it is not comfortable:** over a three-to-four-year runway, *abandonment* stops being the lesser scenario. That is **R21**, it is rated High, and its only real mitigations are **MD4 (BSD-3-Clause)** and the M24 Maven Central mirroring item.

---

### R9. v0.1 is ordered by match-record leverage, and the wizard is in it

> **⛔ SUPERSEDED as a release plan by MD1 (2026-08-07); the ordering survives intact as build order.** Nothing moves to a later release, because there are no later releases — drive, vision and auto are in v0.1 along with everything else. The *reasoning* below is preserved exactly and is what `ROADMAP.md` §5 implements: order by match-record leverage, and put the one capability with no ecosystem substitute early. Concretely: M1 platform → M2 units → M3 seam → M4 mechanisms → M5 sim → M6 tunables → **M7 wizard** → M8 template, and only then M9 drive, M10 vision, M11 auto.
>
> **The uncomfortable consequence MD1 forces into the open:** R9's whole argument was that the wizard must exist *during a season*. At solo pace **M7 lands 2027-04-24 — one week after the 2027 season ends.** At +2 committers it lands 2026-12-15, before kickoff. That four-and-a-half-month gap is the strongest argument anywhere in these documents for the second-committer question in Part 5.

**Chosen:** self-test + health monitors first (P0), tunables not cuttable (P5), **Wizard Lite in v0.1** (P6, 3.0 pw, `TuningSupervisor` + all 12 aborts + `SysIdSweep` + `FeedforwardRegression` + `HoldBisectionStep` + `BreakawayRampStep` + `LqrSuggestStep` + `PredictStep` + `Lessons` + `Coach` + two recipes). ~~**The drive funnel, vision and the auto DSL move to v0.2.**~~ *(That clause is deleted by MD1; they are M9, M10 and M11 of one release.)*

**Rejected (a):** revision 1's ordering, which put `SelfTest` in v0.2 and the entire tuning wizard in v0.2 at 14 pw across a Jan–Apr 2027 window whose own capacity model yields 4–10 pw. It therefore would not have existed during the 2027 season. Worse, revision 1's stated downside ship excluded the tunables package entirely: **the library requested to teach tuning would have shipped unable to change a gain.**

**Rejected (b):** keeping vision and auto in v0.1 and deferring the wizard. Every team already has a working drivetrain (Tuner X, an AdvantageKit template, YAGSL), working vision (the AdvantageKit vision template, LimelightHelpers) and working autos (PathPlanner's GUI and docs). **The wizard has no substitute.** Deferring the domains that have ecosystem alternatives in favour of the one that does not is the correct trade — and it makes incremental adoption the *normal* path rather than an escape hatch, which is the strongest thing the reordering buys.

**Rejected (c):** shipping `OdometryReport` a version later than the drive layer. Odometry accuracy gates alignment and every score-on-the-move capability. Shipping wheel-radius characterization with no instrument to tell a team whether it worked makes the gating rule fire unconditionally on every alignment, and therefore be ignored on day one. *(Preserved under MD1 as a hard constraint on M9: `OdometryReport.outAndBack()` / `.squareTest()` ship **in the same milestone** as the drive layer. R8 in the risk register.)*

---

### R10. "The First 30 Minutes" becomes "The First Session — plan two hours"

> **⚠ AMENDED by MD2 + MD3 (2026-08-07).** The two-hour honesty, the Command-Robot-template correction and the deleted compile-time claim all stand. Two changes: **MD3 withdraws the "zero *vendor* `requires`" rule entirely** (see the consequence paragraph, already rewritten below), and **MD2 changes what step 1 produces** — `pumpkin init` now yields a whole working project from a template variant rather than a wiring session, which is the largest single improvement to the first-session number in the design. The two hours are **not** re-estimated downward here, because the estimate was never dominated by wiring; it was dominated by the cold GradleRIO build, AdvantageScope/Elastic unfamiliarity, and reading. Claiming MD2 buys back an hour would be exactly the overclaiming the adversarial review removed.

**Chosen:** four honest blocks totalling 100–170 minutes; `pumpkin init` as step 1; the **Command Robot** template, not Timed Skeleton; `WPILibNewCommands.json` declared in `requires[]`; an 8–20 minute cold-build estimate; and the sentence *"the compiler will not let you build a `PositionConfig` missing `reduction`"* **deleted** — a fluent builder terminating in `.build()` cannot enforce that in Java, and the design's own validation section confirms it is a runtime check.

**Rejected:** revision 1's 30-minute table, which (a) started from a template that does not install `WPILibNewCommands`, so every `Subsystem`, `Command`, `Trigger` and `SysIdRoutine` in the library fails to resolve with nothing naming the cause; (b) pasted a config block that did not compile against its own import list; (c) claimed a compile-time guarantee Java cannot provide; (d) budgeted 3 minutes for a cold GradleRIO build; (e) assumed AdvantageScope and Elastic fluency in 6 minutes; and (f) did not use the CLI that ships in the same version.

**Consequence:** the "zero `requires`" rule became **"zero *vendor* `requires`"** — and that rule is now **withdrawn entirely by maintainer decision 3.** `PumpkinLib.json` declares two entries: `WPILibNewCommands.json`, which ships offline with the WPILib installer, and `AdvantageKit.json`, which does not. **Kickoff-day install is no longer possible before AdvantageKit publishes.** See [`ROADMAP.md` §4.2](ROADMAP.md).

---

### R11. Honest numbers replace the headline ratio

> **⚠ AMENDED by MD1 (2026-08-07) — the denominator changed meaning, so the sentence must change.** The **measurement is unchanged**: ~132 lines / 4 files vs ~1,620 lines / 14 files, ~12× less code at ~3.5× fewer files, with `TunerConstants.java`, `FieldPoses.java`, `RobotIds.java`, the PathPlanner path files and `Main.java` excluded from **both** columns and named, and the command published in `docs/measuring.md`. What changed is that **"computed against the v0.1 scope only" no longer means anything**, because v0.1 is now everything. The correct restatement is **"computed against the M1–M11 internal build — platform, mechanisms, tuning, telemetry, sim, drive, vision and auto"** — which is the same set of capabilities the old sentence meant, now named by milestone instead of by version. Every "v0.2" / "v0.3" qualifier in the table below is likewise dead text and must be rewritten as a milestone reference in `README.md` and `DESIGN.md` §10.7. **This is a wording correction, not a new claim; the ratio does not move.**

**Chosen:** ~132 lines / 4 files vs ~1,620 lines / 14 files — **~12× less code at ~3.5× fewer files, computed against the v0.1 scope only**, with `TunerConstants.java` (~250 lines, Tuner-X generated), `FieldPoses.java`, `RobotIds.java`, the PathPlanner path files and `Main.java` excluded from **both** columns and named. The measurement command is published in `docs/measuring.md`.

**Withdrawn:** "~330 lines vs ~3,990, roughly 12×" (computed against a library that requires v0.3, i.e. after the 2027 season) and "roughly 40× against 4738" (never apples-to-apples, which the same section conceded — quoting it at all was a liability).

**Also withdrawn or rewritten:**

| Claim | Status |
|---|---|
| "makes a small team's robot code look like an elite team's robot code" | Rewritten: closes the **software** gap; §4.1 states the ceiling |
| "ten competition-day health checks" (stated four times; one domain doc said six; the package tree listed seven) | **Seven monitor types, eight registered health slices**, named, with `BuiltinMonitorCountTest` asserting both |
| "the first Limelight simulation in FRC" | "we are not aware of another Limelight wire-format simulator; treat it as unvalidated until R7's real-hardware comparison passes" |
| "Nothing in the ecosystem does this" (rejection reasons) | "No FRC library we surveyed exposes a per-frame, per-camera, named rejection taxonomy" |
| "including the current-spike homing routine, because `ElevatorSim` produces a real current spike at its travel limit" | `ElevatorSim` *saturates*; whether the transient is detectable by the shipped debounce is an assumption. `PumpkinSim` now prints peak simulated stall current at boot, and sim-to-hardware transfer is a **G1 gate item** *(now the M3/M5 gate — MD1)* |
| "it does strictly more than any of the four repos" | Restated against v0.1 scope, with the v0.3 dependency named *(restate against **M1–M11** — MD1)* |
| **"teleop gets slip limiting"** *(added 2026-08-07)* | True **only** while depth lever **L13** is unfired. If L13 fires, `TractionLayer`/`SkidDetector` become detect-and-report and this claim **must be deleted everywhere it appears in the same commit.** |
| **"installable on kickoff morning before any vendor publishes"** *(added 2026-08-07)* | **Withdrawn — MD3.** See the rewrite table in MD3, cost 1. |

---

# Part 4 — Rejected critiques

Recommendations from the adversarial review that we **did not adopt**, with reasoning. These are recorded so a future reader does not re-raise them, and so a future reader can overrule them with full information.

### X1. The blanket "no unchecked throw reachable from `Mechanism.periodic()`" ArchUnit rule

**Reviewer asked for:** an ArchUnit rule forbidding the throw of any unchecked exception from any method transitively reachable from `Mechanism.periodic()` or `MotorIO.*`.

**We rejected the rule and adopted the substance.** The behavioural fix is applied in full: `throw new IllegalStateException("…This is a PumpkinLib bug.")` is replaced by degrade-and-name — a sticky `kError` alert, `setNeutral()`, and a latched no-op routing-fault flag.

**Why the rule itself is rejected:** transitive reachability means every array index, every division, and every WPILib or vendor call can throw. The rule either flags the entire JDK or is vacuous. Two enforceable rules replace it: (1) no explicit `throw` statement in `org.pumpkinlib.mechanism..` or `org.pumpkinlib.hardware..` outside constructors, static factories and `Validation`; (2) a bytecode test asserting the `try/catch(Throwable)` wrapper exists in `Mechanism.periodic()`. The invariant itself is asserted by a behavioural test.

---

### X2. `MatchImpact` as a bare `boolean blocksMatch` parameter

**Reviewer asked for:** `PumpkinAlert.error(String group, String text, boolean blocksMatch)` — required at every call site, no default, no single-argument overload.

**We adopted every substantive demand** (required everywhere, no default, no overload, driver mirror capped at 3, `Ready` rolls up blocking only, `AlertBudgetTest` as a CI gate, `.expectAbsent()` demotion) **and changed only the encoding** to `MatchImpact.BLOCKS_MATCH` / `MatchImpact.PIT_ONLY`.

**Why:** `Alerts.error("Arm", "leader disconnected", true)` is unreadable at the exact call site that matters, and it gets copy-pasted with the wrong literal by precisely the audience this library targets. A two-value enum costs nothing and reads correctly.

---

### X3. Deleting both vision structs in favour of flat parallel arrays

**Reviewer asked for:** delete `implements StructSerializable`, `TargetObservationStruct` and `VisionFrameStruct` entirely, and log flat parallel arrays per camera (`TagIds` int[], `TagTx` double[], `TagTy` double[], …), on the grounds that decision 19 says "zero custom structs."

**We adopted half.** `VisionFrame` genuinely cannot be a struct and its declaration is deleted. **`TargetObservation` stays a struct.**

**Why:** the "records cannot contain arrays" premise is wrong for this case — WPILib's struct schema supports **fixed-length** arrays (`double cornerTxRad[4]`), and every other component of `TargetObservation` is a scalar, a `Rotation2d` or a `Transform3d`. `getSize()` is a compile-time constant, so it is legitimately `StructSerializable`. The alternative — six-plus parallel `double[]` topics per camera, index-aligned by convention — reintroduces exactly the botpose-index-arithmetic class of bug this library exists to delete, and it makes a per-target record unreadable in AdvantageScope's table view.

The real hazard behind decision 19 is AdvantageKit's documented **>100 ms first-log-of-a-new-struct** blocking cost. That is addressed directly: both structs are registered and written once during `robotInit()`, so the cost lands at boot and never at match start. Decision 19 is restated as *"zero custom structs for mechanism and drive telemetry; exactly two in vision, both provably fixed-size, both pre-warmed."*

*(**MD3 note:** that >100 ms cost is now unconditional rather than backend-dependent, which makes the `robotInit()` pre-warm a hard requirement rather than a precaution. It does not change the conclusion — it removes the "only if you chose AdvantageKit" hedge from it.)*

---

### X4. Putting `GravityMode` and the tolerance on `Gains`

**Reviewer asked for:** `Gains` to carry `GravityMode gravityMode` with `withGravity(double kG, GravityMode mode)`, and `Gains.toleranceSi` as the single stored tolerance with `ControlConfig` storing none.

**We rejected both placements** and went the other way: `Gains` is seven doubles; `GravityMode` and tolerance both live on `ControlConfig`.

**Why:** the reviewer correctly identified that revision 1 was *internally contradictory* — the `Gains` record, the nested enum and the example did not agree — and any consistent resolution fixes the compile error. But the right resolution is the opposite of the one proposed. Gravity mode is a property of the mechanism's **geometry** (derived from the `Axis`; a team should never type a gravity mode for an elevator), and tolerance is a property of its **policy**. Neither is a gain. Putting them on `Gains` means the tuner, the persistence store, the exporter and the NT schema all have to understand mechanism configuration. See A3(b).

---

### X5. Splitting the `Superstructure` router work into v0.1

> **⛔ SUPERSEDED as a version placement by MD1 (2026-08-07); the ordering argument survives unchanged.** There is no v0.2. The router is **M14** (2.0 pw), after interlocks land in M4. The reason is identical to the one below and is now written into M14's gate: **the router's API is validated against 9143-A's actual CorAl geometry (R11) before it freezes.** Depth lever **L7** (−0.6) is the pressure valve — keep bounding-box, `forbidUnless` and `escapeCommand()`, drop `corridor` and `characterizeTransitions()` — at the cost that "carry it tucked" must be expressed as an interlock.

**Not asked for directly**, but implied by several findings that treat collision avoidance as core. It stays **v0.2**, with `Interlock`s in v0.1.

**Why:** interlocks are 80% of the safety value at 20% of the cost, and the router's API is the one thing in the design that must be validated against 9143-A's real CorAl geometry (R11) before it freezes. Shipping it against a synthetic geometry and then breaking it in-season is worse than shipping it later.

---

### X6. Cutting `PhotonCameraIO` and the `AutoStep` DSL "now" to fund the wizard

> **⛔ SUPERSEDED by MD1 (2026-08-07).** Nothing is cut by domain, so there is nothing to fund the wizard *from* — the wizard is simply built early, at M7, and everything else is built later. `PhotonCameraIO` is **M16**; the `AutoStep` DSL is **M11**, with **both** PathPlanner and Choreo. The pre-committed cut list C1–C5 is deleted and replaced by depth levers L1–L19. The nearest survivors of this critique are **L14** (Choreo degrades to load-and-run, −1.2, which also dodges the `[UNVERIFIED]` ChoreoLib accessor risk R12 rather than solving it) and **L6** (`CameraArbiter` → first-valid-wins, −0.6). Both are levers to be fired under pressure, not the plan.
>
> **The accounting objection below survives and got worse, not better.** Cutting 1.6 pw out of a 16.25 pw plan did not close a 3.0 pw gap. Firing **all nineteen** depth levers recovers 18.9 pw out of 74.0 and moves the solo central date only from 2029-12-08 to 2029-02-02. Scope-shaving is a small lever. Capacity is the large one.

**Reviewer asked for:** promote cut items C3 (`PhotonCameraIO`, −0.6) and C2 (`AutoStep` DSL, −1.0) from "contingent cut" to "cut now," and defer `SimulatedLimelight`-adjacent work, to pay for Wizard Lite.

**We adopted the goal and rejected the accounting.** Wizard Lite is in v0.1. But the funding does not come from cutting two items out of vision and auto — it comes from **moving the whole vision, drive and auto layer to v0.2** (R9). Cutting 1.6 pw out of a 16.25 pw plan to fund a 3.0 pw addition does not close the gap; it produces a v0.1 with a half-built vision layer and a half-built auto layer, which serves nobody.

---

# Part 5 — Open questions for the maintainer

Four of the seven previously open questions were decided on 2026-08-07 and now live in **Part 0** as MD1–MD4. They are binding and are not relitigated anywhere in these documents.

Three remain genuinely undecided. They are listed **in the order they block work**, with the milestone each one blocks, because under MD1 "we'll decide before v0.1" is now a **three-to-four-year** deferral and is no longer an acceptable answer to any of them.

---

### Q1. Who is the second committer? — **now the highest-leverage open question in the project**

**Was:** a release-gate checkbox in R8/G4 — *"a second person has push access and has cut one release"* — filed under bus-factor risk.

**Is now:** the **single largest determinant of whether PumpkinLib exists in a useful timeframe**, and it is no longer primarily a risk question at all. It is the schedule.

| Staffing | Central v0.1 | vs. solo | The thing that actually changes |
|---|---|---|---|
| **Solo** | **2029-12-08** (mid-2030 with carrying cost) | — | The tuning wizard (M7) lands **2027-04-24 — one week after the 2027 season ends.** |
| **+1 mentor-grade committer** | **2028-09-12** | **−15 months** | M7 lands 2027-01-18, ten days after kickoff. M1–M8 complete 2027-02-16, inside the build season. |
| **+2 committers** | **2028-04-07** | **−20 months** | **M7 lands 2026-12-15 — before the 2027 kickoff.** The wizard exists for the 2027 season. |
| **+2 and a student team of 3** | **2027-11-10** | **−25 months** | The **only** staffing in the table that lands v0.1 before a kickoff (2028-01-08). |
| **A student team of 3 alone** | **2029-03-06** | −9 months | **Worst case 2031-09-05 — worse than solo's central case.** Net-negative for 3–6 months; suitable for docs fixtures, adoption fixtures, CI, `CycleStats`, `ValueExporter`, template variants. **Never M3, M4, M7, M9, M10, M14 or anything commanding a voltage.** |

**Why this is the question and not merely *a* question:** MD1 removed domain-cutting as a lever. Depth (L1–L19) recovers 18.9 pw and buys about ten months at solo pace. **One committer buys fifteen. Two buy twenty.** Everything else in this document is an argument about what to build; this is the only argument about whether it gets built while it still matters (R20).

**What "mentor-grade" has to mean, stated so the bar is not negotiated down later:** someone who can be the **second reviewer on voltage-commanding code** (R19 requires one permanently), who can independently cut a release, and who can review every FATAL-tier validation message (R9's residual). A contributor who cannot do those three things reduces the schedule but does not reduce the bus factor, and R21 is unaffected by them.

**Blocks:** nothing technically — but every week it stays open is a week spent on the solo row of that table. **Decision needed: as early as possible; the value of an answer decays monotonically.**

---

### Q2. Is the repository public during the build, and when is anything announced?

**Decided already:** v0.1 itself **ships publicly** — a 74 pw first release held private serves nobody — and **nothing is announced on Chief Delphi before M24** (`ROADMAP.md` §8). That is deliberate and it buys three concrete things: **no API stability obligation** (breaking changes are free for three years, which is the one real advantage of not shipping and is worth several person-weeks), **no in-season support obligation to strangers** (R15's policy has no audience because there are no strangers), and **no reputational exposure** (R4 cannot fire against something unannounced).

**Still undecided:** whether the **repository** is public from day one during the M1–M23 build, and which GitHub org owns it.

**The genuine tension, both directions:**

- **Public early** invites R4 (a hostile first read of an incomplete maximalist library) at the worst possible moment — when there is a 74 pw design document and 3.5 pw of code — and it makes the "breaking changes are free" property socially harder to exercise even though it remains technically true. It also starts the R20 clock in public.
- **Private until M24** means v0.1 arrives as a **very large first release from an unknown author with no commit history** — which `ROADMAP.md` §10 names as *the worst possible shape for a hostile first read*. It also makes Q1 materially harder: a private repository is a poor recruiting instrument for the committer that Q1 needs.

**Note that these two problems point in opposite directions and cannot both be fully mitigated.** A partial answer worth considering: public repository, **no announcement**, README first screen stating plainly that it is pre-release, unsupported, and not accepting issues before v0.1.

**Blocks:** **M8**, which publishes vendordep JSONs and a GitHub Pages Maven repository. Solo that is **2027-06-09**; at +2 it is **2027-01-07**. **Decision needed before M8 begins**, not before it ends.

---

### Q3. Name, domain and Maven group

`PumpkinLib` / `org.pumpkinlib` (package root) / `dev.pumpkinlib` (Maven group) assumes the **`pumpkinlib.dev` domain is available and stays paid for**. Fallback for the **Maven group only** is `io.github.<org>`; **the package root never changes** under any outcome.

**MD1 changes the weight of this.** Under the old plan the domain had to survive one season. Under MD1 it must be continuously renewed for **three to four years before it fronts a release**, and then indefinitely after. A lapsed domain behind a published Maven group is a supply-chain hazard, not an inconvenience. **MD4 partially covers this**: BSD-3-Clause means a fork can republish under different coordinates if the domain is ever lost, which is why the licence decision and this one are related.

**Also folded in here:** which GitHub org owns the repository (shared with Q2), since it determines the `io.github.<org>` fallback and the Pages URL that `PumpkinLib.json` will point at forever.

**Blocks:** **M8** — the vendordep JSON URLs and the Pages Maven path are baked into every fork of `PumpkinTemplate` from that point on, and changing them later breaks `pumpkin update --library` for every team that has one. **Decision needed before M8.**

---

### Carried forward as decided, for reference

| Previously open | Status |
|---|---|
| **Scope of v0.1** | **CLOSED — MD1.** Full one-stop-shop scope. No domain deferred. |
| **Library vs template** | **CLOSED — MD2.** Both; template is the primary front door. |
| **AdvantageKit: backend or requirement** | **CLOSED — MD3.** Required dependency. Kickoff-day install knowingly traded away. |
| **License** | **CLOSED — MD4.** BSD-3-Clause. [`LICENSE`](LICENSE). |
| **Hosting mechanism** | **Decided (A31):** GitHub Pages static Maven + Maven Central mirror. The *org* is Q2/Q3. |

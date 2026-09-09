# Rootstock documentation

Three pages are written for you. Everything else in this repository is written for whoever builds
the library, and reading it first is the most common way to lose an afternoon.

## If you are trying to use Rootstock

| Start here | |
|---|---|
| **[getting-started.md](getting-started.md)** | From an empty WPILib project to a mechanism moving in simulation. Every command, every import. Says plainly which steps have been run and which have not. |
| **[concepts.md](concepts.md)** | The ten ideas the API is built on, and a glossary. Twenty minutes, and it is the difference between the API reading as a design and reading as noise. |
| **[troubleshooting.md](troubleshooting.md)** | Symptom to cause, built from the messages the library actually prints. |

| Then | |
|---|---|
| **`rootstock/build/docs/javadoc/index.html`** | The API reference, after `./gradlew build` or `./gradlew :rootstock:javadoc`. This is the best documentation in the project: every public type has a class comment, and the ones on `PositionConfig`, `PositionMechanism` and `Gains` are worked tutorials. The compiler checks them, which prose cannot. |
| **[`../examples/`](../examples)** | What working code exists, in the order it is worth reading. `first-mechanism/` is four copyable files for one elevator. `rootstock/src/test/java/org/rootstock/example/` is a complete three-mechanism robot with a safety model, interlocks, driver bindings and an autonomous routine; it lives in the test tree because the build compiles it there, which is also why it cannot be wrong, and its own comments explain every place it differs from what the design documents print. |

## The maintainer's documents

**These are not your manual.** They are honest and they are large, they argue decisions rather than
explain usage, and parts of them describe options that were deleted or code that does not exist.
Nothing in this section is required reading to use the library.

| | |
|---|---|
| **`DESIGN.md`** | The full design, about 67,000 words. Sections 11 and 11b are a first-session walkthrough, and they start from a template repository that does not exist yet. |
| **`DECISIONS.md`** | Why each choice was made, with the alternatives that were rejected. |
| **`ROADMAP.md`** | What ships when, at what pace, with the arithmetic. |
| **`REVIEW.md`** | An adversarial review **of the design documents** as they stood on 2026-08-07, not of the shipped Java. Findings in it are defects in the specification. Read as a description of the library, it is misleading. |

The six documents under `design/` are implementation specifications, one per domain. Their status
lines say when they were written, not whether the code exists, so here is that separately:

| | Code exists? |
|---|---|
| `design/01-core-mechanisms.md` | **Yes** (M1 through M4): `org.rootstock.config`, `units`, `hardware`, `mechanism`, `superstructure`. |
| `design/02-tuning.md` | **Yes** (M6, M7): `org.rootstock.tuning`, including the wizard. Two recipes of six. |
| `design/03-vision.md` | **No.** There is no `org.rootstock.vision` package. Everything in that document is a specification for code that has not been written. |
| `design/04-telemetry-replay-viz.md` | **Partly** (M5): `org.rootstock.telemetry` exists. The 3D visualization is M19 and does not. |
| `design/05-drivetrain-auto.md` | **No.** There is no `org.rootstock.drive`, `auto` or `field` package. |
| `design/06-platform-compday.md` | **Yes** (M1): `org.rootstock.core`, the alerts, health monitors and self-test. The distribution and template sections are M8 and do not exist. |

## What is not written yet

These are referenced by name elsewhere in the repository. None of them exists, and naming them
here is cheaper for a reader than a broken link.

| Planned page | What it would hold | Milestone |
|---|---|---|
| `docs/removing-rootstock.md` | The plain-WPILib equivalent of every Rootstock concept, side by side, per subsystem. The rip-out path the README's support policy points at. | M8 |
| `docs/UPDATING.md` | How to take an in-season patch release. | M8 |
| `docs/graduation.md` | Adopting or dropping the tuning system alone, both directions. | M8 |
| `docs/template-drift/` | Where `rootstock update --template` would write patches for files you have edited. | M8 |
| `docs/triage.md`, `docs/lint.md`, `docs/measuring.md`, `docs/stats.md`, `docs/vision-calibration.md` | Domain guides for milestones that have not landed. | M8 and later |
| `docs/errors.md` | Every error and alert message the library can print, generated from the source so it cannot drift. | not scheduled |

There is also no hosted javadoc site, no vendordep JSON, no published artifact and no template
repository. [getting-started.md](getting-started.md) opens with what that means for you.

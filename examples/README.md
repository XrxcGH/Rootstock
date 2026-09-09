# Examples

Three places to find working Rootstock code, in the order they are worth reading.

## 1. A complete robot: `rootstock/src/test/java/org/rootstock/example/`

An elevator, an arm and a roller, with a superstructure, a collision model, an interlock, driver
bindings, rumble on game-piece capture, and an autonomous routine. About 490 lines across three
files.

| File | What it holds |
|---|---|
| `RobotConfig.java` | Three mechanism configs and the typed setpoint handles that go with them. |
| `RobotContainer.java` | Construction, the `SafetyModel`, the `Superstructure`, the bindings and the auto routine. |
| `ScoringState.java` | The state machine: an enum where each state declares a goal per mechanism. |

**Read this one first.** It lives in the test tree because the Gradle build compiles it there,
which is the only reason to trust any example in this repository over any snippet in a document.
Its own class comments enumerate every place the published design snippets differ from the shipped
API, which makes it the errata as well as the example.

Two things it is not. It is not a runnable robot project: there is no `Robot.java` and no
`Main.java` in it. And its motor specs are simulated, because the test source set is not allowed to
depend on a vendor artifact; `RobotConfig.simulated()` explains exactly what that swaps and what it
leaves alone.

## 2. A minimal robot you can copy: `first-mechanism/`

Four files, one elevator, no superstructure. This is the smallest complete set of files that makes
something move, and it is the shape [`docs/getting-started.md`](../docs/getting-started.md) walks
through. See [`first-mechanism/README.md`](first-mechanism/README.md).

## 3. Three tests that read as worked examples

The test prose in this project is better than the document prose, and it cannot go stale, because
it compiles. These three are worth opening even if you never write a test:

| File | What you learn |
|---|---|
| `rootstock/src/test/java/org/rootstock/config/ValidationTest.java` | Every config error a team can hit, with the exact wording, and why a wrong number is a value rather than an exception. Closest thing this project has to an error catalogue. |
| `rootstock/src/test/java/org/rootstock/mechanism/UntunedGainsTest.java` | What `Gains.UNTUNED` does in each direction, and why the sentinel is NaN and not zero. |
| `rootstock/src/test/java/org/rootstock/sim/RootstockSimTest.java` | Why simulation goes through the real gear-ratio path, and what a wrong ratio looks like when it is not caught. |

`rootstock/src/test/java/org/rootstock/superstructure/InterlockTest.java` is a fourth, and it pins
the exact string the dashboard shows when a transition is refused.

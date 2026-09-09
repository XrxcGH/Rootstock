# Concepts

Ten ideas, in the order you meet them. Read this once and the API stops looking like a pile of
builders.

This page is the middle layer. Above it is the README, which is one paragraph. Below it is
`DESIGN.md` and `design/01` through `design/06`, which are implementation specifications written
for whoever builds the library, not for you. If you want the exact signature of a method, the
javadoc is the reference (`./gradlew :rootstock:javadoc`, then
`rootstock/build/docs/javadoc/index.html`).

---

## 1. A mechanism is data, not a subsystem you write

In plain WPILib you write a `Subsystem` class per mechanism: fields for the motor, a PID
controller, conversion constants, a `periodic()` that reads and writes. Four mechanisms is four
files that mostly say the same thing slightly differently.

In Rootstock you write a **config**: a description of the physical thing. A gearbox. A sprocket. A
current limit. Named positions. Then you hand that description to a **mechanism** class the
library already wrote.

```java
PositionConfig ELEVATOR = PositionConfig.linear("Elevator")....build();  // what it is
PositionMechanism elevator = new PositionMechanism(ELEVATOR);           // what it does
```

There are three of each, and they pair up:

| If the thing... | Config | Mechanism |
|---|---|---|
| goes to a place (elevator, arm, turret, wrist) | `PositionConfig` | `PositionMechanism` |
| holds a speed (flywheel, shooter) | `VelocityConfig` | `VelocityMechanism` |
| just runs (intake roller, feeder, hopper) | `SimpleConfig` | `SimpleMechanism` |

A `PositionMechanism` **is** a plain WPILib `Subsystem`. `setDefaultCommand` works. Requirements
work. Commands you already wrote work. This is a library, not a framework: nothing takes your
`Robot` class away from you.

`PositionConfig.linear(...)` is for something that moves in a line and reports meters.
`PositionConfig.rotary(...)` is for something that swings and reports degrees.

## 2. The reduction and the axis, each applied exactly once

This is the idea that inverts what most students have already learned, so it gets the most space.

The usual habit is to sprinkle conversion arithmetic through robot code: divide by the gear ratio
here, multiply by 360 there, multiply by the drum circumference over in the odometry. It works
until the day somebody changes a sprocket, and then it works incorrectly in four places, one of
which nobody finds until the mechanism drives into a hard stop.

Rootstock splits that arithmetic into exactly two declarations, and applies each of them exactly
once, in one place:

- **`Reduction`** is the gearbox: how many turns of the motor rotor make one turn of the output
  shaft. It is applied **inside the motor controller's own configuration** (Phoenix's
  `SensorToMechanismRatio`, REV's `positionConversionFactor`). Your code never sees it.
- **`Axis`** (`LinearAxis` or `RotaryAxis`) is the geometry: what one turn of the output shaft
  does in the real world. Eleven inches of carriage. One degree of joint. It is applied **once**,
  in `MechanismUnits`, on the way in and out of the motor.

```java
.reduction(Reduction.ofStages(3.0, 4.0))            // 3:1 then 4:1
.axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))  // #25 chain, 22 teeth, 2 cascade stages
```

State the part, not a number you calculated. `sprocket(pitch, teeth, stages)` and
`ofTeeth(driven, driving)` exist so that `describe()` can print the derivation and you can check
it against CAD, instead of printing a bare `0.0444679` that nobody can argue with.

**Nothing in your Java multiplies by a ratio.** If you find yourself typing `/ 360.0` or
`* GEAR_RATIO`, you are working around the library rather than with it.

### The four layers

Every number in Rootstock lives in exactly one of four layers, and which one is never ambiguous:

```
  USER UNITS         SI UNITS           OUTPUT ROTATIONS         ROTOR ROTATIONS
  m / deg     <-->   m / rad     <-->   one turn of the   <-->   inside the vendor
  what you type      what gains and     last shaft before        Phoenix SensorToMechanismRatio
  and read           wpimath use        the geometry             REV positionConversionFactor
       ^                  ^                    ^                         ^
       |                  |                    |                         |
     Axis          MechanismUnits         the MotorIO seam           Reduction
   (geometry)      (deg -> rad)                                       (gearbox)
```

| Quantity | Unit |
|---|---|
| `Gains.kP` | V/m (linear) or V/rad (rotary) |
| `Gains.kV` | V/(m/s) or V/(rad/s) |
| `Gains.kS`, `Gains.kG` | V |
| `MotionConstraints` max velocity, acceleration | **user** units per second, per second squared |
| `Setpoint`, soft limits, tolerances | a typed WPILib `Measure` |
| `PositionMechanism.goal()`, `.measured()` | **user** units: meters or degrees |
| `MotorIO.setPositionGoal(...)` | output-shaft rotations, rotations per second, volts |

"User units" means the unit you declared: **meters** for a `LinearAxis`, **degrees** for a
`RotaryAxis`.

Gains are SI and constraints are user units on purpose. Gains are machine numbers the tuning
wizard measures and a file persists, and they have to stay correct across a change of drum
diameter. Constraints are numbers a driver says out loud: make the elevator go 1.6 meters per
second.

## 3. Gains are volts per SI unit, always

The same steering motor's kP is about **7** on the roboRIO, about **100** in Phoenix, and about
**0.01** in REVLib. That is a spread of four orders of magnitude for one physical behavior, and it
is why gains are untransferable between teams and why the tutorials teach a number you cannot use.

Rootstock has one `Gains` type: seven doubles, every one of them volts per SI unit.

```
kS  volts                     static friction
kV  volts / (unit/second)     velocity
kA  volts / (unit/second^2)   acceleration
kG  volts                     gravity
kP  volts / unit              proportional
kI  volts / (unit * second)   integral
kD  volts / (unit/second)     derivative
```

where "unit" is meters for a linear mechanism and radians for a rotary one. That matches wpimath
exactly, which is the point: a number you read in a WPILib tutorial means the same thing here.

The vendor conversion happens **once**, in a `GainSink`, inside the adapter. Your code never sees
a vendor unit.

**`Gains.UNTUNED` is not a placeholder you leave in.** In simulation it resolves to a first guess
derived from the mass you declared, so your demo moves. On real hardware a mechanism holding it
refuses closed-loop control and raises an alert telling you to run the wizard. Homing and manual
control still work.

## 4. The hardware seam is a goal, never a voltage

`MotorIO` is the one boundary between mechanism logic and a motor controller. There is exactly
one such interface, and the important thing about it is what it is **not**.

The obvious vendor-neutral motor interface is `getPosition()` plus `setVoltage(volts)`, with a
roboRIO PID controller in between. That throws away everything a modern motor controller is worth:
Motion Magic's on-motor profile, FOC, `SensorToMechanismRatio`, fused CANcoders, setpoint
latching, REVLib 2026's on-controller gravity feedforward.

So Rootstock's seam is `setPositionGoal(outputRotations, rotationsPerSecond, arbFeedforwardVolts)`
-- **where to end up**, not what voltage to apply. Whether the backend turns that into
`MotionMagicVoltage`, `SparkClosedLoopController.setReference(...)` or a roboRIO trapezoid profile
is the backend's business.

`ControlLocation` is where that loop actually runs, and it defaults sensibly:

| | |
|---|---|
| `ON_MOTOR_PROFILED` | the motor controller profiles and closes the loop (the default for a TalonFX) |
| `ON_MOTOR_DIRECT` | the motor closes the loop with no profile |
| `RIO_PROFILE_MOTOR_LOOP` | the roboRIO profiles, the motor closes the loop |
| `RIO_FULL` | the roboRIO does everything |

A config's `describe()` prints which one you got **and why**.

**The escape hatch is on page one, not in an appendix.** Anything Rootstock does not model, you do
yourself, on the real device:

```java
elevator.io().as(TalonFXMotorIO.class)
    .ifPresent(io -> io.applyRaw(cfg -> cfg.Audio.BeepOnBoot = false));
```

## 5. AdvantageKit, and why it is required

AdvantageKit is a required dependency, not one logging option among several. Rootstock does not
install without it. If you have never used it, here is the whole idea.

**What it is.** A logging framework that records, every 20 ms, every *input* your robot code reads:
motor positions, sensor values, joystick axes, the driver station state. Not just the numbers you
chose to publish, but everything that went into a decision.

**What an IO layer is.** Code that touches hardware is separated from code that decides things. The
hardware-touching part is called an IO layer: it reads a device and fills in a plain data object
once per loop, and nothing else reads that device. `MotorIO` and `MotorInputs` are exactly that,
and **Rootstock already wrote them for you** for motors, gyros, absolute encoders and digital
sensors. You write an IO layer only if you add hardware Rootstock does not model.

**What replay buys.** Because every input was recorded, you can re-run today's match on a laptop
against the real sensor data, add a print statement, and watch the same decisions happen again.
Not a simulation of the match: the match. That is the property Rootstock traded its
install-on-kickoff-morning story for, and it is worth knowing that the trade was deliberate and is
argued at length in `ROADMAP.md`.

**What it costs you.** You cannot install Rootstock until AdvantageKit has published for the
season. Teams on DogLog or plain Epilogue cannot adopt Rootstock without changing loggers; this is
a hard incompatibility, not a migration. The README's
[Adopting one piece](../README.md#adopting-one-piece) table is the honest version of who can and
cannot take this path.

**Setup.** Install the AdvantageKit vendordep **before** anything Rootstock. Then pick one
`LogConfig` factory and understand which:

- `LogConfig.defaults()` -- Rootstock configures and **starts** AdvantageKit's `Logger`. Use this
  if Rootstock is the only thing logging.
- `LogConfig.adoptExistingLogger()` -- your code already called `Logger.start()`; attach to it.

Getting that wrong starts the logger twice, which is a crash at boot rather than a slow
degradation. There is deliberately no no-argument version that guesses.

## 6. Registration: one call, and what it buys

```java
RootstockRegistry.addAll(elevator, arm, roller, superstructure, tuner);
```

That one call is where everything attaches. It routes each object to whichever of these it
qualifies for: telemetry, the health monitors, the pit self-test, live tuning, the command
scheduler, lifecycle hooks, closeables, and the CAN device list.

It is also the single place where **validation** happens. It collects every config problem it
found, runs one global CAN ID conflict scan over the final device set, prints them all together,
and then decides whether to enter `SAFE_MODE`.

Call it once, from `RobotContainer`'s constructor, after your fields are assigned. Then call
`lifecycle().init()` last, from `Robot`'s constructor, so the boot dump reflects what you actually
registered.

## 7. Failures are values, not exceptions

Nothing in a config builder throws. Not a setter, not `build()`. A bad value is **recorded** and
carried along, and every problem the library found is printed together, in one block, at boot.

There are three severities:

| | | |
|---|---|---|
| **FATAL** | structurally impossible: a zero reduction, swapped soft limits, a CAN ID of 64 | the robot boots into `SAFE_MODE` and refuses every command |
| **WARNING** | implausible but runnable: a cruise velocity above free speed, kG of zero on a gravity-loaded arm | a persistent alert; you can still drive |
| **PLACEHOLDER** | a blank rather than a measurement: `Gains.UNTUNED`, a 1:1 reduction, a sim mass nobody weighed | a line on the boot-time first-setup checklist |

The robot **still boots** in every one of these cases. A CAN ID of 64 becomes a sentence you can
read, not an `ExceptionInInitializerError` at 11 pm the night before a competition.

Every message names the mechanism, the field, the value it got, the range it expected, and a
`Fix:` clause with the call to make. There are 295 of those `Fix:` clauses in the core source
(`grep -rn "Fix: " --include=*.java rootstock/src/main/java | wc -l`), which is roughly the size
of the answer this library is trying to be.

## 8. Superstructure and interlocks

A `Superstructure` is for when two mechanisms can hit each other, or when "score at level 4" means
three things happening in a coordinated way.

You write an enum of states, each state declaring a goal per mechanism:

```java
public enum ScoringState implements SuperState {
  IDLE(Map.of()),
  L4(Map.of(elevator, AxisGoal.of(ELEVATOR_L4), arm, AxisGoal.of(ARM_SCORE)));
  // ...
}
```

Then two safety mechanisms sit on top:

- **`SafetyModel`** is geometry. `forbid(...)` marks a rectangle of the (axis A, axis B)
  configuration space as impossible, because that is where the arm hits the crossbar.
  `corridor(...)` names a pose that is safe across a whole range, which is what gives the router
  somewhere to go. `SafetyModel.over(...)` takes **mechanisms**, not configs, because it reads
  their live measured positions, so it is built in `RobotContainer` and not in `RobotConfig`.
- **`Interlock`** is a rule about transitions. "Nothing but IDLE is reachable until both axes have
  homed", with the message the driver station shows when it refuses.

`SuperState` goals are typed. Asking for a scoring level that does not exist is a compile error
rather than a mid-match no-op, because the only `Setpoint` handles in scope are the ones the
config declared.

## 9. Tunables and the wizard

Two different things, often confused.

**A tunable** is one number you can change from the dashboard while the robot runs.
`TuningRegistry.tunable("Vision", "maxTagDistance", 6.0, "m")` publishes
`/Tuning/Vision/maxTagDistance`. Live tuning is on by default and **turns itself off when the FMS
is attached**, re-evaluated every read, so a mid-match connection locks it without anybody
remembering to call anything. You can override that, and doing so raises a warning for as long as
it is active, because a dashboard slider that can change robot behavior mid-match must be visible
in the log next to whatever happened afterwards.

**The wizard** is a guided session that measures kS, kV, kA and kG for you by running real motion
on the robot, fits them with streaming least squares, derives kP and kD, asks you to predict the
outcome before it moves anything, and explains each step in plain language.

```java
private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));
```

Give it a port nobody else uses. Right trigger held past half travel is enable, A accepts, B
retries, X goes back, Y skips, Start aborts and reverts every gain to what it was at the start of
the session, and the D-pad answers a prediction. **Motion is impossible outside Test mode by
construction**, not by convention, so a driver holding a trigger to shoot on a practice field
cannot accidentally authorize raw voltage to an arm.

Today the wizard ships **two recipes: ELEVATOR and FLYWHEEL**. Arm, turret, steer and
drive-velocity are milestone M13 and asking for one throws with a message saying so.

Gains resolve from four sources, lowest priority first: the `Gains` in your config, then
`src/main/deploy/rootstock/gains.json` (committed, reviewed, in git), then
`rootstock/gains.json` in the roboRIO's persistent directory (the Save button, survives a
redeploy), then the live dashboard value, which is ignored entirely under FMS. Merging is per
value, so bisecting kG does not revert kV. A file whose recorded config hash no longer matches the
mechanism is ignored and says so, because gains silently surviving a mechanical change is how a
robot gets destroyed after a rebuild.

## 10. Where the types live

The package split is not arbitrary, but it is not guessable either, and two of the splits cost
beginners real time. The rule: anything that could not depend on WPILib's HAL got put where it can
be unit tested without one.

| Package | What is in it |
|---|---|
| `org.rootstock.config` | `PositionConfig`, `VelocityConfig`, `SimpleConfig`, `MotorGroup`, `MotorSpec`, `Follower`, `FeedbackSpec`, `CurrentLimits`, `MotionConstraints`, `HomingStrategy`, `SensorSpec`, `Setpoint`, `SimConfig`, `SparkModel`, `MotorModel`, `ConfigError` |
| `org.rootstock.control` | **`Gains`**, `ControlLocation`, `NeutralMode` |
| `org.rootstock.units` | `LinearAxis`, `RotaryAxis`, `Range`, `MechanismUnits` |
| `org.rootstock.pure.units` | **`Reduction`** |
| `org.rootstock.mechanism` | `Mechanism`, `PositionMechanism`, `VelocityMechanism`, `SimpleMechanism` |
| `org.rootstock.superstructure` | `Superstructure`, `SuperState`, `SafetyModel`, `Interlock`, `AxisGoal` |
| `org.rootstock.core` | `RootstockRobot`, `RootstockLifecycle`, `RootstockRegistry`, `SafeMode` |
| `org.rootstock.core.spi` | **`LogConfig`** |
| `org.rootstock.core.selftest` | `SelfTest` |
| `org.rootstock.core.alert` | `Alerts` |
| `org.rootstock.telemetry` | `RootstockLog` |
| `org.rootstock.tuning` | `TuningRegistry`, `TunableDouble` |
| `org.rootstock.tuning.wizard` | `TuningWizard` |
| `org.rootstock.hardware` | `MotorIO` and the rest of the seam |

The three that catch everyone:

- **`Gains` is in `control`**, not `units`, even though it is full of units.
- **`Reduction` is in `pure.units`**, not `units`, because it is arithmetic with no WPILib
  dependency at all.
- **`LogConfig` is in `core.spi`**, not `core`, next to nothing you recognize.

## 11. Logging your own values

`RootstockLog` writes into AdvantageKit. Three tiers, and the tier decides what survives a
byte budget:

```java
RootstockLog.critical("Elevator/Homed", elevator.isHomed());              // never dropped
RootstockLog.log("Elevator/HeightAboveFloor", heightMeters, Meters);      // the default
RootstockLog.debug("Elevator/StatorAmps", elevator.statorAmps());         // first to go
```

Pass the WPILib unit when the value has one. It costs nothing and it means AdvantageScope can
label the axis and a human reading the log a week later knows whether that 0.5 was meters or
inches.

Rootstock's own mechanism telemetry is already there under `Rootstock/<Name>/`: `Goal`,
`Setpoint`, `Measured`, `Error`, `Output`, `AtGoal`, `Homed`, `SoftLimitMin`, `SoftLimitMax`, and
a `Homing/` subtree with `Active`, `Strategy`, `ElapsedSec`, `Succeeded` and `AbortReason`. You do
not have to publish any of that yourself.

---

## Glossary

| Term | Meaning |
|---|---|
| **axis** | The geometry of one mechanism: what one output-shaft rotation does. `LinearAxis` or `RotaryAxis`. |
| **boot dump** | Two things share the name. `RootstockLifecycle.init()` prints one to the console: version, mode, hooks, what registered, the CAN scan, safe-mode state. The per-mechanism derivation block is `config.describe()`, and you have to call it. |
| **goal** | Where you want the mechanism to end up. Distinct from **setpoint**, which is where the motion profile wants it to be *this loop*. |
| **hardware seam** | The `MotorIO` boundary. Above it, mechanism logic; below it, a vendor. |
| **interlock** | A rule that refuses a superstructure state transition, with a message. |
| **IO layer** | The code that reads one device and fills a plain data object, once per loop. AdvantageKit's discipline; Rootstock supplies yours for motors and sensors. |
| **output rotations** | One turn of the last shaft before the geometry. The unit the seam speaks. |
| **reduction** | The gearbox. Rotor turns per output turn. Applied inside the vendor config, once. |
| **`SAFE_MODE`** | What the robot enters when a FATAL config error is found. It boots, it reports, it refuses every command. |
| **setpoint** (named) | A `Setpoint` handle from `.setpoint("L4", ...)`. A compile-checked name for a position. |
| **tunable** | A number you can change from the dashboard while the robot runs. |
| **user units** | The unit you typed: meters for a linear axis, degrees for a rotary one. |
| **volts-per-SI** | The one gain convention: every gain is volts per meter, per radian, or per second thereof. |

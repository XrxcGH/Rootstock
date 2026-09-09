# Getting started

The shortest honest path from "I have a robot project" to "my mechanism moves in simulation."

Plan about an hour the first time. Most of that is the cold Gradle build, which downloads WPILib
and runs once.

---

## Read this before you type anything

**There is one way to consume Rootstock today, and it is a Gradle composite build.** Everything
else you may have read is a plan, not a path:

- There is no released version and no git tag.
- Nothing is published to Maven Central, to a GitHub Pages Maven repository, or anywhere else.
- `https://rootstock.dev/vendordep/2026/Rootstock.json` does not resolve. There is no
  `Rootstock.json` anywhere in this repository, not even an unpublished one.
- `RootstockTemplate` and the `rootstock init` command do not exist. They are milestone M8.

What does work is pointing your robot project's Gradle build at a local clone of this repository.
That is what the maintainer's own two teams do, and it is the whole of this page. It is not a
workaround that will be replaced by something better later; it is the internal path, written down.

Nothing here has been run on a roboRIO. Simulation is where you should expect this to work.

---

## Before you start

You need all five of these. Rootstock cannot supply any of them for you.

| | |
|---|---|
| WPILib 2026 installed | Rootstock targets **WPILib 2026.2.2**. A 2025 install will not compile against it. |
| A JDK 17 from Adoptium | The Rootstock build pins Adoptium 17 on purpose. A JetBrains Runtime, which Android Studio and the IntelliJ family bundle, crashes the JVM when WPILib's HAL loads. |
| Git | You are going to clone this repository next to your robot project. |
| A motor controller you have a CAN ID for | Or nothing at all, if you only want simulation. |
| AdvantageKit | A hard requirement, not an option. See [concepts.md](concepts.md#5-advantagekit-and-why-it-is-required) for what it is and why. |

---

## Step 1: clone Rootstock and build it once

Put it beside your robot project, not inside it.

```bash
cd ~/frc
git clone https://github.com/XrxcGH/Rootstock.git
cd Rootstock
./gradlew build
```

That first build downloads WPILib, AdvantageKit, Phoenix 6 and REVLib, compiles about 105,000
lines, runs the test suite and generates the javadoc. It is slow the first time and fast after
that. It should end in `BUILD SUCCESSFUL`. If it does not, stop here: nothing later on this page
can work until it does.

When it finishes you have three jars:

```
Rootstock/rootstock/build/libs/rootstock-2026.0.0-SNAPSHOT.jar
Rootstock/rootstock-phoenix6/build/libs/rootstock-phoenix6-2026.0.0-SNAPSHOT.jar
Rootstock/rootstock-revlib/build/libs/rootstock-revlib-2026.0.0-SNAPSHOT.jar
```

and the API reference, which is the best documentation this project has:

```
Rootstock/rootstock/build/docs/javadoc/index.html
```

Open that file in a browser and leave the tab open. The class comments on `PositionConfig`,
`PositionMechanism` and `Gains` are longer and better than anything in this `docs/` directory,
and they are checked by the compiler, which prose is not.

## Step 2: make a robot project

In VS Code: **WPILib: Create a new project** -> **Template** -> **Java** -> **Command Robot**.

Put it beside the Rootstock clone, so that your layout is:

```
~/frc/
  Rootstock/          <- the clone from step 1
  0000-robot/         <- your project
```

The relative path `../Rootstock` in step 4 assumes exactly this. If you put them somewhere else,
change that one string.

## Step 3: install the AdvantageKit vendordep first

In VS Code: **WPILib: Manage Vendor Libraries** -> **Install new libraries (online)**, then paste
AdvantageKit's vendordep URL.

Take that URL from AdvantageKit's own installation page rather than from memory or from here.
Rootstock pins **AdvantageKit 26.0.2** in its `build.gradle`; install that version or newer.

**One thing that is genuinely unresolved.** AdvantageKit's install may need an
`annotationProcessor` line, and possibly other stanzas, in your `build.gradle` beyond the
vendordep. Rootstock's own design notes flag this as unverified
(`design/06-platform-compday.md`, the paragraph beginning "**[UNVERIFIED]**"), and it is the kind
of mistake that is invisible until the day you try to replay a log. Read AdvantageKit's
installation page and follow it exactly. Do not copy a build file from a forum post.

You do **not** need to install a Rootstock vendordep. There is not one. Step 4 replaces it.

## Step 4: point your build at the clone

Two files in your robot project.

**`settings.gradle`** -- add this line at the top, above everything else:

```groovy
includeBuild('../Rootstock')
```

**`build.gradle`** -- add these to your existing `dependencies { ... }` block:

```groovy
dependencies {
    // ... whatever GradleRIO already put here, unchanged ...

    implementation 'dev.rootstock:rootstock:2026.0.0-SNAPSHOT'

    // Exactly one of these, matching the motor controllers you actually have.
    // Both is legal if you run a mix.
    implementation 'dev.rootstock:rootstock-phoenix6:2026.0.0-SNAPSHOT'
    // implementation 'dev.rootstock:rootstock-revlib:2026.0.0-SNAPSHOT'
}
```

`includeBuild` tells Gradle that any dependency on `dev.rootstock:rootstock` should be satisfied
by the project in `../Rootstock` instead of by a download. The version string is required by
Gradle's syntax and is ignored for the substitution, so you never have to bump it.

**You still need the vendor's own vendordep.** `rootstock-phoenix6` gives you the Rootstock
adapter for a TalonFX. Phoenix 6 itself still comes from CTRE's vendordep, installed the normal
way. Same for REV. If you skip it, the build fails on CTRE's classes, not on Rootstock's.

**About deploying to a roboRIO.** Whether GradleRIO packages a composite-built dependency into
the jar it deploys has not been checked by anyone who wrote this page, and no Rootstock code has
ever run on a roboRIO. Before you deploy, build the jar and look inside it for
`org/rootstock/`. If it is not there, the deploy will fail at boot with a `NoClassDefFoundError`
naming a Rootstock class, and the fallback is to depend on the jar files from step 1 by path
instead. Simulation does not have this question.

## Step 5: write four files

All four go in `src/main/java/frc/robot/`. Copy them whole. Every import is listed, and these
four files compile as printed (see [Verification](#verification) at the bottom).

They are also on disk at [`examples/first-mechanism/`](../examples/first-mechanism), which is where
they are kept and which is what gets compiled. If the two ever disagree, believe the files on disk.

Change the CAN IDs, the gearbox, the sprocket and the soft limits to match your robot. Leave the
structure alone.

### `RobotConfig.java` -- the mechanism, as data

```java
package frc.robot;

import static edu.wpi.first.units.Units.Amps;
import static edu.wpi.first.units.Units.Inches;
import static edu.wpi.first.units.Units.Pounds;
import static edu.wpi.first.units.Units.Seconds;
import static edu.wpi.first.units.Units.Volts;

import org.rootstock.config.CurrentLimits;
import org.rootstock.config.FeedbackSpec;
import org.rootstock.config.HomingStrategy;
import org.rootstock.config.MotionConstraints;
import org.rootstock.config.MotorGroup;
import org.rootstock.config.MotorSpec;
import org.rootstock.config.PositionConfig;
import org.rootstock.config.Setpoint;
import org.rootstock.control.Gains;
import org.rootstock.pure.units.Reduction;
import org.rootstock.units.LinearAxis;

public final class RobotConfig {

  public static final PositionConfig ELEVATOR =
      PositionConfig.linear("Elevator")
          .motors(MotorGroup.leader(MotorSpec.talonFX(20, "rio")))
          .reduction(Reduction.ofStages(3.0, 4.0))          // 3:1 then 4:1 = 12:1
          .axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2)) // #25 chain, 22 teeth, 2 cascade stages
          .feedback(new FeedbackSpec.RotorOnly())
          .softLimits(Inches.of(0.0), Inches.of(55.0))
          .currentLimits(CurrentLimits.of(Amps.of(70), Amps.of(40)))
          .gains(Gains.UNTUNED)                             // see step 8
          .constraints(MotionConstraints.of(1.6, 6.0))      // m/s, m/s^2
          .tolerance(Inches.of(0.5), 0.05, 0.06)
          .manualControl(0.10, 0.30)
          .homing(
              HomingStrategy.currentSpike()
                  .direction(HomingStrategy.Direction.REVERSE)
                  .voltage(Volts.of(-1.5))
                  .currentThreshold(Amps.of(30))
                  .debounce(Seconds.of(0.15))
                  .timeout(Seconds.of(4.0))
                  .seedTo(Inches.of(0.0)))
          .setpoint("STOW", Inches.of(0.0))
          .setpoint("L4", Inches.of(52.5))
          .sim(Pounds.of(24.0), Inches.of(0.0))             // the only sim code you write
          .build();

  public static final Setpoint ELEVATOR_STOW = ELEVATOR.setpoint("STOW");
  public static final Setpoint ELEVATOR_L4 = ELEVATOR.setpoint("L4");

  private RobotConfig() {}
}
```

Two of those imports are in places you would not guess, and it costs a beginner half an hour
every time: **`Gains` is in `org.rootstock.control`**, and **`Reduction` is in
`org.rootstock.pure.units`**, not next to `LinearAxis` in `org.rootstock.units`.
[concepts.md](concepts.md#10-where-the-types-live) explains why, and lists the rest.

`ELEVATOR_STOW` and `ELEVATOR_L4` are worth the two extra lines. A misspelled `"L4"` passed as a
string is a runtime refusal; a `Setpoint` handle is a compile error.

### `RobotContainer.java` -- the mechanism, alive

```java
package frc.robot;

import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import org.rootstock.core.RootstockRegistry;
import org.rootstock.mechanism.PositionMechanism;
import org.rootstock.tuning.wizard.TuningWizard;

public class RobotContainer {

  private final PositionMechanism m_elevator = new PositionMechanism(RobotConfig.ELEVATOR);
  private final CommandXboxController m_driver = new CommandXboxController(0);

  // Give the wizard a port nobody else uses. Its right trigger authorizes raw voltage,
  // which is the driver's "shoot" button on most robots.
  private final TuningWizard m_tuner = TuningWizard.using(new CommandXboxController(2));

  public RobotContainer() {
    // ONE call. Telemetry, health monitors, the pit self-test, live tuning, the CAN ID scan
    // and config validation all attach here.
    RootstockRegistry.addAll(m_elevator, m_tuner);

    // The derivation dump. Nothing calls describe() for you; see step 7.
    System.out.println(RobotConfig.ELEVATOR.describe());

    m_driver.start().onTrue(m_elevator.homeCommand());
    m_driver.y().onTrue(m_elevator.goTo(RobotConfig.ELEVATOR_L4));
    m_driver.a().onTrue(m_elevator.goTo(RobotConfig.ELEVATOR_STOW));
    m_elevator.setDefaultCommand(m_elevator.manual(() -> -m_driver.getLeftY()));
  }
}
```

### `Robot.java` -- the base class

```java
package frc.robot;

import org.rootstock.core.RootstockRobot;
import org.rootstock.core.spi.LogConfig;

public class Robot extends RootstockRobot {

  private final RobotContainer m_container;

  public Robot() {
    super(LogConfig.defaults());
    m_container = new RobotContainer();
    lifecycle().init();   // LAST, after everything has registered
  }
}
```

`LogConfig.defaults()` means Rootstock configures **and starts** AdvantageKit's `Logger`. If your
existing code already calls `Logger.start()`, this is a crash at boot, and you want
`LogConfig.adoptExistingLogger()` instead. There is deliberately no version that guesses.

If you do not want to change your base class, you do not have to. `RootstockRobot` is a thin shim
whose every line delegates to the public `RootstockLifecycle`, and the README's
[Adopting one piece](../README.md#adopting-one-piece) section shows the hand-wired form.

### `Main.java` -- unchanged

GradleRIO generated this for you. It should already read:

```java
package frc.robot;

import edu.wpi.first.wpilibj.RobotBase;

public final class Main {
  public static void main(String... args) {
    RobotBase.startRobot(Robot::new);
  }

  private Main() {}
}
```

## Step 6: run it in simulation

```bash
./gradlew simulateJava
```

`MotorSpec.talonFX(...)` routes to the Phoenix 6 adapter in simulation too, and Phoenix simulates
through its own sim state. That is the point: simulation exercises the same gear ratio path the
real robot will, so a units mistake shows up on your laptop instead of on the field.

## Step 7: read the derivation dump before you touch a stick

Two different things print at startup, and they are worth separating.

**What the library prints on its own.** `lifecycle().init()` writes a block headed
`Rootstock boot dump` to the console: the version, the kill-switch state, the mode, the loop
period, the hooks, what registered and what was skipped, the CAN device scan, the deploy
provenance and the safe-mode state. `RootstockRegistry.addAll(...)` prints a summary line, and, if
it found any, a block of every config problem together.

**What you have to ask for, and should.** The per-mechanism derivation dump is the single most
valuable thirty seconds of a first session, and **nothing in the library calls it for you**. It is
one line, and step 5's `RobotContainer` has it:

```java
System.out.println(RobotConfig.ELEVATOR.describe());
```

That prints your gearbox as you wrote it and as one number, what one output rotation is worth in
inches and in meters, your soft limits in both inches and rotations, your gains with their units
spelled out, where the control loop is going to run and why, the free-speed estimate your cruise
velocity is checked against, and every named setpoint. Without that line the resolved config
reaches only the log, under `/Rootstock/Config/<Name>`, where you can read it in AdvantageScope
but not while you are standing at the robot.

**Check the travel-per-rotation line against your CAD.** The config above should report one
output rotation as 11 inches of carriage travel, printed as `0.279400 m`: a 22-tooth number 25
sprocket is 5.5 inches of chain per turn, doubled by the two cascade stages. If your CAD says 5.5,
you declared a cascade stage you do not have, and you have found it in the console instead of in a
hard stop. This is the check the whole thing exists for.

## Step 8: make it move

Press **Start** to home. The elevator drives down at 1.5 V until the current spikes past 30 A for
150 ms, then seeds its position to zero. Watch `Homing/Succeeded` and `Homing/AbortReason` in
AdvantageScope or on the dashboard.

Then press **Y**.

In simulation it will move: `Gains.UNTUNED` resolves at construction to a first guess derived
from the mass you declared, and an alert says so, ending "They were DERIVED, not measured -- run
the tuning wizard before trusting them on hardware."

**On a real robot it will refuse, on purpose.** A mechanism holding `Gains.UNTUNED` will not run a
closed loop, and it raises an alert that says so. Manual control and homing still work. To get
real gains, run the tuning wizard: put the robot in **Test** mode, hold the wizard controller's
**right trigger** past half travel, and follow it. A accepts, B retries, X goes back, Y skips,
Start aborts and reverts every gain to what it was when the session started.

Two limits worth knowing now. The wizard ships recipes for **ELEVATOR and FLYWHEEL only**; arm,
turret, steer and drive-velocity recipes are milestone M13 and asking for one throws with a
message saying so. And the wizard cannot command a volt outside Test mode, by construction, no
matter what any button is doing.

Where the result goes, in three steps, because the distinction matters. Saving on the robot writes
`rootstock/gains.json` in the roboRIO's persistent directory: it survives a redeploy and a reboot,
and is cleared only by re-imaging. `ValueExporter` writes `gains-for-commit.json` for a human to
copy. The committed answer is `src/main/deploy/rootstock/gains.json`, which is in git, is reviewed
as a diff, and is what survives a student graduating.

One rule worth knowing before it bites you: **a gains file whose recorded config hash does not
match the mechanism is ignored**, and it raises a blocking error rather than being applied. Change
the gearbox and yesterday's gains stop being used, on purpose.

---

## What to read next

- **[concepts.md](concepts.md)** -- twenty minutes, and it is the difference between this API
  reading as a design and reading as noise.
- **[troubleshooting.md](troubleshooting.md)** -- when it does not move.
- **[`examples/`](../examples)** -- what working code there is, in the order it is worth reading.
  The three-mechanism robot at `rootstock/src/test/java/org/rootstock/example/` is the one to
  open when you outgrow a single elevator: it has a superstructure, a collision model, an
  interlock and an autonomous routine, and it is compiled by the build, so unlike every snippet
  in the design documents it cannot be wrong.
- **`rootstock/build/docs/javadoc/index.html`** -- the API reference from step 1.

---

## Verification

This project has been burned by documentation that was never run. So, plainly:

**Checked while writing this page.** The four Java files in step 5 were written to disk and
compiled against the real core classpath, with no errors:

```bash
cd /path/to/Rootstock
javac -cp "$(cat .cp-core.txt)" -d /tmp/probe examples/first-mechanism/src/main/java/frc/robot/*.java
# exit 0
```

The three jar paths and the javadoc path in step 1 were checked to exist. The coordinates
`dev.rootstock:rootstock`, `dev.rootstock:rootstock-phoenix6` and `dev.rootstock:rootstock-revlib`
and the version `2026.0.0-SNAPSHOT` were read out of `build.gradle` and off the jar filenames.
The AdvantageKit version `26.0.2` was read out of `build.gradle`.

**Not checked.** Every `./gradlew` command on this page, including `./gradlew build` and
`./gradlew simulateJava`. The `includeBuild` line and the two `implementation` lines have not
been run in a real robot project by anyone who wrote this. The AdvantageKit vendordep URL is
deliberately not printed here because it was not verified. Deploying to a roboRIO has never been
done with any part of this library.

If you walk this path and a step is wrong, that is a bug in this page, and it is worth reporting
as one.

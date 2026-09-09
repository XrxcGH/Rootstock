# Troubleshooting

Organized by what you are looking at, not by what the library is doing. Find your symptom, read
across.

**Before anything else: read the console.** Rootstock prints one block per mechanism at startup and
one summary line from `RootstockRegistry.addAll(...)`, and it collects every problem it found and
prints them together rather than dying on the first one. Roughly 295 of the library's error
messages carry a `Fix:` clause naming the exact call to change. If a message is on your screen,
the answer is usually in it.

---

## Nothing moves at all, and the console says SAFE_MODE

**What you see.** A block like this, and every command refused:

```
Rootstock config error [FATAL] in "Elevator"

  field    limits.min / limits.max
  value    min = 1.3970 m, max = 0.0000 m
  expected limits.min < limits.max

  The robot has BOOTED so you can read this. Every mechanism is neutral and
  refuses every command until the fault above is fixed and the code redeployed.
```

**What it means.** Rootstock found at least one FATAL config error. FATAL means structurally
impossible: a zero reduction, swapped soft limits, a CAN ID outside 0 to 62 (Phoenix and REVLib
both stop at 62), a feedback ratio that contradicts the declared gearbox, two devices claiming the
same CAN ID on the same bus.

**Fix.** Read the `field`, `value` and `expected` lines. They name the exact builder call. Change
it and redeploy. `SAFE_MODE` is not something you clear at runtime; it is a statement that the code
as deployed describes a robot that cannot exist.

The full list is also published to `/Rootstock/Driver/SafeModeErrors`, and `/Rootstock/Driver/SafeMode`
is the boolean a dashboard reads to grey itself out.

---

## One mechanism does not move; the others do

Work down this list in order. It is roughly in order of how often each one is the answer.

| Check | What you will see | Fix |
|---|---|---|
| Is the vendor adapter installed? | An alert: `no Rootstock adapter is installed for a TalonFX (vendor "Phoenix 6"), so this mechanism will not move. Fix: add the matching artifact` | Add `dev.rootstock:rootstock-phoenix6` or `dev.rootstock:rootstock-revlib` to your build, alongside that vendor's own vendordep. See [getting-started step 4](getting-started.md#step-4-point-your-build-at-the-clone). |
| Is it disabled by the kill switch? | The `addAll` summary reports it as `skipped; disabled by the runtime kill switch` | Remove the name from `src/main/deploy/rootstock/disabled.txt`. |
| Are the gains still `UNTUNED`? | `gains-untuned: closed-loop control is refused because kP is the UNTUNED placeholder` | See the next section. |
| Has it homed? | `not homed, so its reported position is a guess and atGoal() will never be true` | Schedule `homeCommand()`, or declare a strategy that needs no motion. |
| Is the device answering? | `Elevator leader (...) not responding. Expected: answering status signals every loop.` | Check CAN wiring, the device ID against the config, and that the device has power. |
| Is it registered? | Nothing about it in the registry section of the boot dump | You never passed it to `RootstockRegistry.addAll(...)`, or you passed a field that was still null at the time. A null in the `addAll` list is reported as skipped, and it is almost always a field assigned after the `addAll` call rather than before it. |
| Did `periodic()` throw? | `periodic() threw and the mechanism was commanded neutral` | The message carries the exception. The mechanism is neutral and the scheduler is still running, so the rest of the robot keeps working. |

---

## It moves in simulation but refuses on the real robot

**Almost certainly `Gains.UNTUNED`.** This is deliberate, not a bug.

`Gains.UNTUNED` carries a NaN kP. In simulation it resolves at construction to a first guess
derived from the mass you declared, so the demo moves, and an alert says the gains were derived
rather than measured.
On real hardware it does not resolve, and the mechanism refuses closed-loop control with:

```
Elevator/gains-untuned: closed-loop control is refused because kP is the UNTUNED
placeholder. Expected measured gains. Fix: run the tuning wizard, or set
.gains(Gains.pid(...)) on the config. Manual control and homing still work.
```

Nobody's converged gains are shipped as pasteable literals, because a number that came off another
team's elevator is not a number about your elevator.

**Fix.** Run the tuning wizard (see below), or set `.gains(Gains.pid(kP, kI, kD)....)` yourself if
you already know the numbers. Homing and `manual(...)` work either way, so you are not stuck.

---

## The mechanism moves the wrong way

Rootstock does not have a global "invert" flag you toggle until it looks right. Three separate
things can be backwards, and they have different fixes:

| Backwards thing | Where it is declared | Symptom |
|---|---|---|
| The motor's direction | `MotorSpec.talonFX(20, "rio").inverted(true)` | Positive goal moves the mechanism the wrong way, and manual control is reversed too. |
| A follower's direction | `.follower(spec, Follower.OPPOSED)` vs `Follower.SAME` | Two motors fight; stator current is high and nothing moves much. Health reports a stall. |
| The homing direction | `HomingStrategy...direction(Direction.REVERSE)` and the sign of `.voltage(...)` | Homing drives away from the hard stop and times out, or trips the limit switch at the far end. |

The sign of the homing voltage comes from `.direction(...)`, not from the sign you type. If the
limit switch at the *other* end asserts during homing, the abort reason is `OPPOSITE_LIMIT`, which
means exactly this: the drive direction is mis-signed.

---

## It moves, but it never gets there, or will not hold

| Symptom | Look at | Likely cause |
|---|---|---|
| `AtGoal` never goes true | `Rootstock/<Name>/GoalError`, and `.tolerance(...)` in the config | The tolerance is tighter than the mechanism can actually hold. About half of the smallest move you care about is a sane starting tolerance. |
| It stops short and sits there | `Rootstock/<Name>/Output`, `SoftLimitMin`, `SoftLimitMax` | A soft limit set inside the real travel, or a current limit too low to overcome gravity. |
| It sags when it gets there | `kG` in `config.describe()` | kG is zero or too small on a gravity-loaded mechanism. This is reported as a WARNING at boot, not silently. |
| It oscillates | `Gains/kP`, `Gains/kD` | Too much kP for the mechanism's inertia. The wizard derives kP and kD from a measured plant instead of from a guess. |
| It is fast enough in sim and slow on the robot | `MotionConstraints` against the free-speed estimate in `config.describe()` | A cruise velocity above what the motor can actually deliver is a WARNING at boot, and it makes the profile a fiction. |

---

## Everything is off by a constant factor

This is a units problem, and the config's own derivation dump catches it in ten seconds.

Nothing prints it for you. Add one line to `RobotContainer`, next to your `addAll` call:

```java
System.out.println(RobotConfig.ELEVATOR.describe());
```

Among other things it prints:

```
One output rotation  0.279400 m  (0.279400 m in SI)
```

The same resolved config also reaches the log at `/Rootstock/Config/<Name>`, which is where to
look if the robot is already packed up.

**Compare that number against CAD.** If your elevator carriage moves eleven inches per rotation of
the drum shaft and this line says 5.5, you declared one cascade stage where you have two. If the
number is off by exactly your gear ratio, your `Reduction` is being applied where it should not be.

The two declarations that produce that number are:

```java
.reduction(Reduction.ofStages(3.0, 4.0))            // rotor turns per output turn
.axis(LinearAxis.sprocket(Inches.of(0.25), 22, 2))  // what one output turn does
```

If you are correcting a factor anywhere else in your Java, you have found the bug rather than
fixed it. Nothing above the hardware seam should multiply by a ratio.

---

## Homing never finishes

`Rootstock/<Name>/Homing/AbortReason` names the cause. It is an enum with fourteen distinct
failures plus `NONE`, because "homing failed" is not something anyone can act on at an event.

| `AbortReason` | What it means |
|---|---|
| `TIMEOUT` | It ran out of time and never found its reference. Check the direction, the voltage and whether the timeout is longer than a full-travel move. |
| `OPPOSITE_LIMIT` | The limit switch at the *other* end asserted. Your drive direction is mis-signed. |
| `NO_TRIGGER_SIGNAL` | A current-spike strategy with an unreadable stator current (a NaN). The backend is not subscribed to that signal, or the device is not answering. |
| `NO_ABSOLUTE_SOURCE` | `absoluteSeed()` with no absolute encoder attached to this mechanism. |
| `ABSOLUTE_DISAGREEMENT` | The absolute sensor and the rotor estimate disagree by more than the declared tolerance. One of them is wrong; do not guess which. |
| `FOLLOWER_DISAGREEMENT` | A follower's position drifted from its leader's. Usually a follower inversion. |
| `DEVICE_RESET` | The device rebooted mid-routine and came back with factory configuration. |
| `DISCONNECTED` | The device stopped answering mid-routine. |
| `DISABLED` | The robot was disabled. Homing runs while enabled or not at all, deliberately: it drives a mechanism into a hard stop. |
| `SAFE_MODE` | A FATAL config error is outstanding. Fix that first. |
| `MISCONFIGURED` | The strategy itself has problems; it was never safe to run. The config errors say which. |
| `NO_STRATEGY` | You did not declare `.homing(...)`. |
| `CONFIG_APPLY_FAILED` | A device configuration the routine depends on did not land. |
| `INTERRUPTED` | Something cancelled it: a command interrupt, or a new goal. |

Also useful: `Homing/Active`, `Homing/ElapsedSec`, `Homing/TriggerValue` and `Homing/Succeeded`.
`TriggerValue` is the number the strategy is watching, so you can see how close it got.

`Homing/LimitsSuspended` publishes whether the routine has temporarily suspended device soft
limits, and `Homing/LimitsRestoreVerified` publishes whether they came back. Both are worth
watching if a mechanism behaves strangely immediately after homing.

---

## Dragging a slider on the dashboard does nothing

Three possibilities, in order:

1. **The FMS is attached.** Live tuning is on by default and turns itself off when the FMS is
   attached, re-evaluated on every read. This is not something you can forget to do. If you
   genuinely need it at an event, `TuningRegistry.allowUnderFms()` opts in, and it raises a
   `BLOCKS_MATCH` warning for as long as it is active. There is no silent version.
2. **Somebody called `setTuningEnabled(false)`.** That turns tuning off everywhere.
3. **You are looking at the wrong key.** Standalone tunables publish under `/Tuning/<namespace>/<key>`.
   The wizard's own tables are under `/RootstockTuner/`.

Related, and it catches teams after a rebuild: **a `gains.json` whose recorded config hash does not
match the mechanism is ignored**, and it raises a blocking error rather than applying stale
numbers. If your code default is `Gains.UNTUNED`, the mechanism then refuses closed-loop control,
so the symptom is a mechanism that worked last week and now will not move. That is deliberate:
gains silently surviving a gearbox change is how a mechanism gets destroyed.

---

## The tuning wizard will not arm

Holding the right trigger does nothing and a message appears. The wizard checks eleven
preconditions and publishes the first one that fails, naming the mechanism and the change that
fixes it. They are, in the order checked:

1. Travel limits exist and are real.
2. The safety envelope validates, so there is a band to stop inside.
3. Stator current is readable (or `allowNoCurrentSensing()` was called).
4. The mechanism is inside the band right now.
5. The robot is enabled.
6. This is not a replay.
7. **This is Test mode and no FMS is attached.**
8. The mechanism is homed.
9. The position reference is one a supervisor can trust.
10. Absolute and rotor-derived positions agree.
11. A gravity-loaded mechanism is in BRAKE, or the coast risk was acknowledged.

Numbers 5, 7 and 8 are what catch people. Motion is impossible outside Test mode **by
construction**: during teleop and autonomous the wizard advances its own state machine and
publishes narration but cannot command a volt, no matter what any button is doing.

Two more things that look like failures and are not:

- **The wizard refuses to leave IDLE and names a controller port.** Its controller shares a port
  with a driver or operator map. Right trigger means "shoot" to a driver and "authorize raw
  voltage" to the wizard. Move it to its own port, or call
  `acknowledgeSharedController(...)`, which logs the choice verbatim.
- **`Rootstock does not ship a tuning recipe for ARM yet.`** Only **ELEVATOR** and **FLYWHEEL**
  recipes exist today. Arm, turret, steer and drive-velocity are milestone M13. The message says
  so and suggests tuning with sliders for now.

If the wizard stops mid-run with:

```
Stopped: 1.50 V applied to Elevator for 0.50 s and it moved slower than 0.0050 m/s.
Fix: check the breaker, check the CAN ID, and check whether it is already against a hard stop.
```

that is the stall detector, and it is telling you something true about the robot rather than about
the wizard.

---

## A red alert I do not recognize

Rootstock's built-in health monitors run seven checks in a round robin. These are the ones you are
most likely to see.

| Alert text starts with | Monitor | What to do |
|---|---|---|
| `resting voltage was ... below the ... V floor` | Battery | Put a charged battery in. The message calls this the single most common lost match, and it is right. |
| `bus voltage ... is below the ... V sag limit` | Battery | Check the battery, the main breaker and the battery leads, and lower your current limits. |
| `The roboRIO cut outputs.` | Brownout | Fix the battery or lower your current limits. |
| `bus ... at NN% utilisation` | CAN bus | Lower the status-frame rates on devices you do not read. |
| `bus ... error counters rose since enable` | CAN bus | Check connectors and terminators before this becomes a bus-off. |
| `Is the code on this robot what is in git?` | Deploy | You deployed from a dirty working tree. Harmless in the shop; worth knowing at an event. |
| `Every binding on that map is dead. Plug the controller into slot ...` | Driver station | A controller is in the wrong slot, or the DS is not in Xbox mode. |
| `Check /Rootstock/Loop/Domain/ to see which domain is spending the time.` | Loop time | Something is over its 20 ms budget. That key says which. |
| `motor temperature ... exceeds the ... C threshold` | Mechanism | Let it cool, then check for a mechanical bind or an over-aggressive current limit. |
| `stalled: NN A of stator current at N rot/s` | Mechanism | A mechanical jam, a hard stop, or a soft limit set past the physical travel. |
| `the device has reset N time(s) since boot` | Mechanism | It came back with factory configuration: no soft limits, no current limits, no gains. Check power wiring and the CAN bus before the next match. |

---

## Build and boot problems

| Symptom | Cause | Fix |
|---|---|---|
| `package org.rootstock.config does not exist` | Your robot project is not seeing the library | Check `includeBuild('../Rootstock')` in `settings.gradle`, that the relative path is right, and that `./gradlew build` inside the Rootstock clone succeeds on its own first. |
| `cannot find symbol: class Gains` | Wrong import | `Gains` is in `org.rootstock.control`. `Reduction` is in `org.rootstock.pure.units`. `LogConfig` is in `org.rootstock.core.spi`. The full table is in [concepts.md](concepts.md#10-where-the-types-live). |
| The robot crashes at boot, in AdvantageKit's `Logger` | `Logger.start()` was called twice | You used `LogConfig.defaults()` while your own code also starts the logger. Use `LogConfig.adoptExistingLogger()`. |
| A JVM crash with no Java stack trace, inside `msvcp140.dll` | The wrong JVM | Gradle picked a JetBrains Runtime, which Android Studio and the IntelliJ family bundle. It crashes when WPILib's HAL JNI loads. Use an Adoptium JDK 17. |
| `NoClassDefFoundError` naming a Rootstock class, on the roboRIO | The deployed jar does not contain the library | Composite-build deployment to a roboRIO has never been verified with this library. Check the jar for `org/rootstock/` before deploying. |

---

## What this page cannot help you with

Nothing in Rootstock has been run on a roboRIO. If your problem is on real hardware and it is not
in the table above, you are ahead of the documentation, and the useful thing to do is capture the
console output and the log.

There is no `docs/errors.md` catalogue of every message yet, no issue tracker workflow, and no
`rootstock doctor --bundle`. Those are planned; see [docs/README.md](README.md) for what exists
today and what does not.

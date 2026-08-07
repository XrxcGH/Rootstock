# PumpkinLib Domain 06 — Platform, Distribution, 2027 Survival, and Competition Day

**Status:** design complete, implementable.
**Author:** Platform domain architect.
**Date:** 2026-08-06.
**Targets:** WPILib 2026.2.x (Java 17) today → WPILib 2027 / SystemCore (Java 25) as the real product.

---

## 1. Scope & Responsibilities

This domain owns everything that is true of *the robot program as a whole* rather than of any one mechanism. Concretely:

| # | Responsibility | Owned artifacts |
|---|---|---|
| 1 | Gradle project layout, package tree, artifact split | root `build.gradle`, `settings.gradle`, `buildSrc/` |
| 2 | Distribution: vendordep JSONs, Maven hosting, GradleRIO consumption, `vendor-json-repo` listing | `vendordep/*.json`, `gradle/publish.gradle` |
| 3 | Versioning scheme, branch strategy, and the **2027 `edu.wpi.first` → `org.wpilib` migration seam** | `org.pumpkinlib.core.compat`, `gradle/wpi-rename-2027.gradle`, ArchUnit rules |
| 4 | The companion template repo and the `pumpkin` CLI's project scaffolding | `PumpkinTemplate` repo |
| 5 | Robot identity + per-robot config overlays, with a build-time deploy gate | `org.pumpkinlib.core.identity`, `pumpkinCheckDeploy` task |
| 6 | Alerts, health monitoring, and the automated pre-match self-test | `org.pumpkinlib.core.alert`, `.health`, `.selftest` |
| 7 | Power / current-limit budgeting, pneumatics, LEDs, rumble | `org.pumpkinlib.core.power`, `.pneumatics`, `.led`, `.hid` |
| 8 | FMS / DriverStation derivation (`MatchContext`) | `org.pumpkinlib.core.match` |
| 9 | Deploy metadata (git provenance) and tuned-config backup/restore | `org.pumpkinlib.core.config` |
| 10 | The NetworkTables namespace contract every other domain publishes into | `org.pumpkinlib.core.nt` |
| 11 | The one-line robot-side entry point that wires all of the above | `org.pumpkinlib.core.PumpkinRobot` |

**Explicitly NOT in this domain:** mechanism abstraction (02), swerve/drive (03), vision (04), autonomous (05), logging backends (07), PID tuning UX (08). This domain defines the *seams* those domains plug into and nothing more.

### 1.1 Design stance restated for this domain

* **Nothing here reimplements a working tool.** WPILib already ships `Alert`, `PowerDistribution`, `RobotController`, `LEDPattern`, `DataLogManager`, `WebServer`. WPILib ships *zero* checks that produce Alerts, *zero* arbitration for LEDs, and *zero* health registry. We supply content and coordination over WPILib's transport. (Dossier `web-ecosystem.json` → "WPILib Alert API as a fault-surfacing channel": *"The transport exists and dashboards already render it — but WPILib ships no actual alerts."*)
* **Zero mystery.** Every alert names the device, the measured value, the threshold, and the config key that set the threshold. Every self-test step names the expectation it failed.
* **Sim-first.** Every class in this domain runs headless with no HAL hardware. `PowerMonitor`, `MatchContext`, `SelfTest`, `LedController` all work under `./gradlew simulateJava` and in JUnit.
* **Escape hatch always.** Every wrapper exposes the raw object: `PowerMonitor.raw()` returns the `PowerDistribution`, `LedController.rawBuffer()` returns the `AddressableLEDBuffer`, `PumpkinAlert.raw()` returns the WPILib `Alert`.

---

## 2. Integration Points

What this domain **needs from** other PumpkinLib domains. These are hard contracts; each is a one-method interface so no domain has to depend on this one heavily.

```java
package org.pumpkinlib.core.health;

/** Anything that can report faults. Implemented by mechanisms, drive, vision, LEDs. */
public interface HealthSource {
  /** Stable name, used as the NT subtable and the Alert group. */
  String healthName();

  /**
   * Called by {@link HealthMonitor} at most ONCE PER ROBOT LOOP, and only on the loop whose
   * cycle index selects this source (round-robin — see §8.3). NEVER called for every source
   * in the same loop, and NEVER gated on a wall clock. Must not block; must not allocate
   * per-fault; must complete in well under 1 ms.
   */
  void pollHealth(FaultCollector out);
}
```

```java
package org.pumpkinlib.core.selftest;

/** Anything that can prove it works. Implemented by mechanisms, drive, vision. */
public interface SelfTestable {
  String selfTestName();
  SelfTestRoutine selfTestRoutine();   // see §8.4
}
```

| From domain | What Platform needs | Why |
|---|---|---|
| **02 Mechanism** | `Mechanism implements HealthSource, SelfTestable`; `Mechanism.declaredStatorLimit()` / `declaredSupplyLimit()` returning `Current`; `Mechanism.resolvedConfig()` returning a `StructSerializable` record | Health registry, self-test sequencing, `PowerBudget` summation, config snapshot |
| **03 Drive** | `Drive implements HealthSource, SelfTestable`; a `BooleanSupplier` slot for alliance flipping | We supply `MatchContext::isRed` — this single substitution kills the #1 competition-day auto failure |
| **04 Vision** | `VisionSystem implements HealthSource` with per-camera connectivity; `SelfTestable` that verifies each camera sees ≥1 tag | "Camera dropped off" must be an Alert, not a silent pose freeze |
| **05 Auto** | Reads `MatchContext.isRed()` and `MatchContext.allianceKnown()`; publishes the chooser under `/Pumpkin/Auto/` | Auto must refuse to run if alliance is unknown |
| **07 Telemetry** | Implements `org.pumpkinlib.core.nt.TelemetrySink` (§4.6); provides `metadata(String,String)` callable **before** the logger starts; provides the match-aware WPILOG rename hook | AdvantageKit's `recordMetadata` is write-once pre-`start()`; we must inject `BuildConstants` there |
| **08 Tuning** | Owns `Tunable`; must consult `org.pumpkinlib.core.match.FmsPolicy.tunablesLocked()` on every read | DogLog's semantic — tunables are inert when FMS-attached — copied verbatim |
| **All** | Publish only under `/Pumpkin/<Domain>/...` (§4.6) | One namespace so AdvantageScope/Elastic layouts we ship actually work |

What this domain **gives** every other domain: `PumpkinAlert`, `RobotIdentity`, `MatchContext`, `DeployInfo`, `PumpkinDashboard`, the NT namespace, and `PumpkinRobot`'s lifecycle callbacks.

---

## 3. Project & Package Structure

### 3.1 Root package: `org.pumpkinlib`

Decided. Rationale:

* Reverse-DNS of `pumpkinlib.dev`, which we will own and which will host the docs, the vendordep JSONs, and the Maven repo. A GitHub-org-derived package (`io.github.<user>`) hard-codes a personal account into every import for a decade.
* Matches WPILib 2027's own move to a non-institutional root (`org.wpilib`), so `import org.pumpkinlib.*` sits visually beside `import org.wpilib.*` and reads as a peer, not a fork.
* Maven group `dev.pumpkinlib` (Central requires a verifiable domain; `dev.pumpkinlib` is verified by DNS TXT on `pumpkinlib.dev`). Group ≠ package root is normal and harmless.

### 3.2 Gradle multi-project layout

```
PumpkinLib/                                   (repo root — the LIBRARY. Not a robot project.)
├── settings.gradle
├── build.gradle                              # version, java toolchains, spotless, archunit, publishing
├── gradle.properties                          # pumpkinVersion, wpilibVersion2026, wpilibVersion2027
├── buildSrc/
│   └── src/main/groovy/
│       ├── pumpkin.java-conventions.gradle    # Java 17 source level, -Werror, javadoc
│       ├── pumpkin.publish-conventions.gradle # maven-publish → Pages repo + Central
│       └── pumpkin.wpi-variant.gradle         # THE 2027 SEAM (see §5.3)
│
├── pumpkin-pure/                # ZERO WPILib imports. Java 17. 100% unit-testable, no HAL.
├── pumpkin-core/                # THIS DOMAIN. WPILib only. No vendor deps.
├── pumpkin-mechanism/           # domain 02
├── pumpkin-drive/               # domain 03
├── pumpkin-vision/              # domain 04  (WPILib-only pose math + SPI; no PhotonVision dep)
├── pumpkin-auto/                # domain 05
├── pumpkin-telemetry/           # domain 07  (sink SPI + NT4 sink; no AdvantageKit dep)
├── pumpkin-tuning/              # domain 08
├── pumpkin-sim/                 # WPILib physics sim wiring; desktop+athena
├── pumpkin-testkit/             # JUnit 5 harness: HAL init, SimHooks stepping, scheduler ticking
│
├── adapters/
│   ├── pumpkin-phoenix6/        # requires Phoenix6 vendordep
│   ├── pumpkin-revlib/          # requires REVLib vendordep
│   ├── pumpkin-photonvision/    # requires photonlib
│   ├── pumpkin-limelight/       # vendors LimelightHelpers-equivalent; no vendordep needed
│   ├── pumpkin-pathplanner/     # requires PathplannerLib
│   ├── pumpkin-choreo/          # requires ChoreoLib
│   ├── pumpkin-advantagekit/    # requires AdvantageKit
│   ├── pumpkin-doglog/          # requires DogLog
│   ├── pumpkin-elastic/         # vendors ElasticLib properly (see §9.4); no vendordep needed
│   └── pumpkin-maplesim/        # requires maple-sim (OPTIONAL, beta upstream)
│
├── tools/
│   └── pumpkin-cli/             # desktop-only. `pumpkin init|gen|doctor|snapshot`. NOT deployed.
│
├── vendordep/                   # the published JSON files (§4.2)
├── layouts/                     # Elastic + AdvantageScope layout JSONs we ship (§9.4)
└── docs/                        # docusaurus; every snippet extracted from a compiled test (§4.8)
```

Separate repos (deliberately not subprojects):

* `PumpkinTemplate` — the GitHub template robot project (§6).
* `pumpkin-scout` — TBA/Statbotics desktop CLI (§12). Different release cadence, different dependency stack, must never enter a robot jar.

### 3.3 One jar or several? — **Several artifacts, few vendordeps.**

Decision: **11 published Maven artifacts, 1 core vendordep JSON + 6 optional adapter vendordep JSONs + 1 convenience "everything" JSON.**

Justification, in priority order:

1. **Kickoff-week vendor lag is the hard constraint.** In Jan 2026 AdvantageKit's swerve templates shipped *weeks late* because they depended on vendors who had not published yet (`web-ecosystem.json` painPoint #1: jonahb55 — *"[is] not yet released since they depend on libraries from vendors who have not published 2026 versions"*). If `pumpkinlib-core` had a compile dependency on Phoenix 6, PumpkinLib could not ship on kickoff day 2027. Core depends on **WPILib only**. A team can install PumpkinLib on kickoff morning and add vendor adapters as they land.
2. **`requires[]` is an install-failure multiplier.** YAGSL's live vendordep declares five hard `requires` (REVLib, Phoenix6, Phoenix5, Studica, ThriftyLib) — a REV-only team is forced to install Phoenix 5. Splitting the vendordep means a REV-only team installs `PumpkinLib.json` + `PumpkinLib-REVLib.json` and never sees CTRE.
3. **Multiple artifacts ≠ multiple installs.** One vendordep JSON may list many `javaDependencies`; YAGSL ships `YAGSL-java` *and* `org.dyn4j:dyn4j` from one JSON. So `PumpkinLib.json` pulls core + pure + mechanism + drive + vision + auto + telemetry + tuning + sim + testkit in a single install.
4. **Jar size is irrelevant; classpath poisoning is not.** A pure-Java jar of ~1 MB is nothing. But a class that `import com.ctre.phoenix6.hardware.TalonFX` and is *never loaded* costs nothing at runtime — `NoClassDefFoundError` fires at class-load, not at classpath-build. That is exactly why adapters must be separate artifacts *and* why we never reference an adapter class from core. Core discovers adapters via `ServiceLoader`, never `Class.forName` string literals.

Published artifacts (`dev.pumpkinlib:<artifactId>:<version>`):

| artifactId | Depends on | In `PumpkinLib.json`? |
|---|---|---|
| `pumpkinlib-pure` | nothing | ✅ |
| `pumpkinlib-core` | pure, WPILib | ✅ |
| `pumpkinlib-mechanism` | core | ✅ |
| `pumpkinlib-drive` | core, mechanism | ✅ |
| `pumpkinlib-vision` | core | ✅ |
| `pumpkinlib-auto` | core, drive | ✅ |
| `pumpkinlib-telemetry` | core | ✅ |
| `pumpkinlib-tuning` | core | ✅ |
| `pumpkinlib-sim` | core, mechanism | ✅ |
| `pumpkinlib-testkit` | core | ✅ |
| `pumpkinlib-elastic` | core | ✅ (no vendordep needed — ElasticLib is a single file we vendor) |
| `pumpkinlib-phoenix6` | core, mechanism, Phoenix 6 | `PumpkinLib-Phoenix6.json` |
| `pumpkinlib-revlib` | core, mechanism, REVLib | `PumpkinLib-REVLib.json` |
| `pumpkinlib-photonvision` | core, vision, photonlib | `PumpkinLib-PhotonVision.json` |
| `pumpkinlib-pathplanner` | core, auto, PathplannerLib | `PumpkinLib-PathPlanner.json` |
| `pumpkinlib-choreo` | core, auto, ChoreoLib | `PumpkinLib-Choreo.json` |
| `pumpkinlib-advantagekit` | core, telemetry, AdvantageKit | `PumpkinLib-AdvantageKit.json` |
| `pumpkinlib-maplesim` | core, sim, maple-sim | `PumpkinLib-MapleSim.json` |

### 3.4 Java package tree (this domain only)

```
org.pumpkinlib.core                 Entry point + lifecycle: PumpkinRobot, PumpkinLib (version, init), PumpkinException.
org.pumpkinlib.core.compat          THE 2027 SEAM. The only package allowed to touch year-volatile WPILib API. See §5.
org.pumpkinlib.core.nt              NT namespace constants, Pumpkin*Publisher helpers, TelemetrySink SPI.
org.pumpkinlib.core.identity        RobotId, RobotIdentity, IdentityResolver, Overlay<T>.
org.pumpkinlib.core.match           MatchContext, MatchInfo, FmsPolicy, MatchPhase.
org.pumpkinlib.core.alert           PumpkinAlert, AlertRegistry, Severity, AlertBridge.
org.pumpkinlib.core.health          HealthSource, Fault, FaultCollector, HealthMonitor, RobotHealth,
                                      SliceScheduler (the ONE round-robin, §8.3.1).
org.pumpkinlib.core.health.builtin  CanBusMonitor, BatteryMonitor, RailMonitor, BrownoutMonitor,
                                      DeployMonitor, LoopTimeMonitor, DsMonitor.
org.pumpkinlib.core.diag            PumpkinTracer (per-section loop-time budgets, §8.3).
org.pumpkinlib.core.selftest        SelfTestable, SelfTestRoutine, SelfTestStep, SelfTestResult, SelfTest, Expect.
org.pumpkinlib.core.power           PowerMonitor, PowerChannel, PowerBudget, EnergyTracker.
org.pumpkinlib.core.pneumatics      Pneumatic, PneumaticsHealth, CompressorPolicy.
org.pumpkinlib.core.led             LedController, LedBackend, LedState, LedRegion, LedBackends.
org.pumpkinlib.core.hid             Rumble, RumblePattern, RumbleScheduler, ControlMap, ControlBinding.
org.pumpkinlib.core.config          DeployInfo, ConfigRegistry, ConfigSnapshot, PersistentStore.
org.pumpkinlib.core.dashboard       PumpkinDashboard, Notify, DashboardTab.
org.pumpkinlib.core.util            EdgeDetector, Debouncer2, Cached<T>, Rate, PeriodicRunner.
```

`org.pumpkinlib.pure.*` (separate artifact, zero WPILib): `Gearing`, `Interval`, `LookupTable`, `Rolling` (sag/statistics windows), `Fixed` (fixed-point helpers). Everything in `pumpkin-pure` is unit-testable with plain JUnit and no HAL.

---

## 4. Distribution

### 4.1 Pure Java, zero JNI — non-negotiable

PumpkinLib ships **no native code**. `jniDependencies: []`, `cppDependencies: []`. Consequences and why this is right:

* Going native means adopting `wpilibsuite/vendor-template`'s three-library structure (Java lib / C-symbols driver lib with an explicit `symbols.txt` / native C++ lib), cross-compiling for 4–5 platform triples, and re-cutting binaries every time the platform set changes — **which it just did**: `linuxathena` → `linuxsystemcore` for 2027 (confirmed live in photonlib's 2027-alpha vendordep).
* Every proven zero-infrastructure FRC distribution is pure Java: DogLog, YAGSL, ThriftyLib.
* Nothing this library does needs native code. Physics sim, NT publishing, and health polling are all pure JVM work.

**C++ support is out of scope, permanently.** We say so in the README. ~90% of FRC teams use Java; a C++ port doubles the maintenance surface for <10% reach and would make the 2027 `frc::` → `wpi::` migration twice as expensive.

### 4.2 The vendordep JSON — exact schema and skeleton

Schema authority is the validator, not prose docs: `https://raw.githubusercontent.com/wpilibsuite/vendor-json-repo/main/check.py` (verified 2026-08-06).

**Required top-level keys:** `fileName`, `name`, `version`, `uuid`, `mavenUrls`, `jsonUrl`, `javaDependencies`, `jniDependencies`, `cppDependencies`.
**Optional:** `frcYear`, `wpilibYear`, `requires`, `conflictsWith`.

**Year rule (verified from `check.py`, and this corrects a common misreading):**
* Years **2026 and earlier, plus `2027_alpha1`** → use `frcYear`, and the file must **not** contain `wpilibYear`.
* Years **2027 onward** (excluding `2027_alpha1`) → use `wpilibYear`, and the file must **not** contain `frcYear`.
* Exactly one must be present. Both, or neither, fails validation.

Cross-checked against a live 2026 vendordep: PathplannerLib 2026.1.2 carries `"frcYear": "2026"` and no `wpilibYear` (verified by fetching `https://3015rangerrobotics.github.io/pathplannerlib/PathplannerLib.json`, 2026-08-06). DogLog's 2027-alpha carries `"wpilibYear": "2027_alpha5"`.

Sub-object schemas (verified from `check.py`):

* `javaDependencies[]` → `groupId`, `artifactId`, `version` (all required).
* `jniDependencies[]` → the above + `isJar` (bool, required), `validPlatforms` (string[], required), `skipInvalidPlatforms` (bool, opt), `simMode` (string, opt).
* `cppDependencies[]` → the above + `libName` (required), `configuration`, `headerClassifier`, `sourcesClassifier`, `binaryPlatforms`, `skipInvalidPlatforms`, `sharedLibrary`, `simMode` (all opt).
* `requires[]` → `uuid`, `errorMessage`, `offlineFileName`, `onlineUrl` — **all four required**.
* `conflictsWith[]` → `uuid`, `errorMessage`, `offlineFileName` — all three required (no `onlineUrl`).

#### 4.2.1 `PumpkinLib.json` — the core vendordep, 2026 line

```json
{
  "fileName": "PumpkinLib.json",
  "name": "PumpkinLib",
  "version": "2026.1.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "frcYear": "2026",
  "jsonUrl": "https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json",
  "mavenUrls": [
    "https://pumpkinlib.dev/repo",
    "https://repo1.maven.org/maven2"
  ],
  "javaDependencies": [
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-pure",       "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-core",       "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-mechanism",  "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-drive",      "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-vision",     "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-auto",       "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-telemetry",  "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-tuning",     "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-sim",        "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-elastic",    "version": "2026.1.0" },
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-testkit",    "version": "2026.1.0" }
  ],
  "jniDependencies": [],
  "cppDependencies": []
}
```

Note: **no `requires` block.** Core needs nothing but WPILib. This is the whole point.

#### 4.2.2 `PumpkinLib-Phoenix6.json` — an adapter vendordep

```json
{
  "fileName": "PumpkinLib-Phoenix6.json",
  "name": "PumpkinLib-Phoenix6",
  "version": "2026.1.0",
  "uuid": "2e6c9d18-4b77-4f0a-8c53-b1a7e9d2c604",
  "frcYear": "2026",
  "jsonUrl": "https://pumpkinlib.dev/vendordep/2026/PumpkinLib-Phoenix6.json",
  "mavenUrls": [ "https://pumpkinlib.dev/repo", "https://repo1.maven.org/maven2" ],
  "javaDependencies": [
    { "groupId": "dev.pumpkinlib", "artifactId": "pumpkinlib-phoenix6", "version": "2026.1.0" }
  ],
  "jniDependencies": [],
  "cppDependencies": [],
  "requires": [
    {
      "uuid": "<PHOENIX6_UUID>",
      "errorMessage": "PumpkinLib-Phoenix6 needs CTRE Phoenix 6. Install it from https://v6.docs.ctr-electronics.com/en/stable/docs/installation/installation-frc.html then re-run Build Robot Code.",
      "offlineFileName": "Phoenix6-frc2026-latest.json",
      "onlineUrl": "https://maven.ctr-electronics.com/release/com/ctre/phoenix6/latest/Phoenix6-frc2026-latest.json"
    },
    {
      "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
      "errorMessage": "PumpkinLib-Phoenix6 needs PumpkinLib core. Install https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json first.",
      "offlineFileName": "PumpkinLib.json",
      "onlineUrl": "https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json"
    }
  ]
}
```

`<PHOENIX6_UUID>` and the other vendors' UUIDs are copied verbatim from each vendor's own published JSON at release-cut time — **never invented**. Known-good values already in hand: REVLib `3f48eb8c-50fe-43a6-9cb7-44c86353c4cb`, PathplannerLib `1b42324f-17c6-4875-8e77-1c312bc8c786` (both verified from live vendordeps). A CI task `verifyRequiresUuids` fetches every `onlineUrl` in every `requires` block and asserts the returned JSON's `uuid` matches — a broken `requires` UUID silently disables the check, which is worse than not having it.

**UUIDs are assigned once and never change.** They identify the vendordep across versions and years. The 2027 line of `PumpkinLib.json` reuses `7b3f0a52-…`; only `version`, `wpilibYear`, `jsonUrl`, and the dependency versions change.

#### 4.2.3 The 2027 line

```json
{
  "fileName": "PumpkinLib.json",
  "name": "PumpkinLib",
  "version": "2027.0.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "wpilibYear": "2027",
  "jsonUrl": "https://pumpkinlib.dev/vendordep/2027/PumpkinLib.json",
  "mavenUrls": [ "https://pumpkinlib.dev/repo", "https://repo1.maven.org/maven2" ],
  "javaDependencies": [ /* same artifactIds, version 2027.0.0 */ ],
  "jniDependencies": [],
  "cppDependencies": []
}
```

Same UUID, different `jsonUrl`, `frcYear` → `wpilibYear`. **One vendordep JSON per (library-year × WPILib-year)**, because the year is baked into the file and validated against the `vendor-json-repo` bundle directory. `jsonUrl` and `mavenUrls` must be stable forever — GradleRIO re-resolves them on every build, and an online-installed vendordep's cache is cleared if the machine does not reconnect within 30 days.

### 4.3 Maven hosting — GitHub Pages static Maven, mirrored to Maven Central

**Decision: publish to a static Maven repo served from GitHub Pages at `https://pumpkinlib.dev/repo`, and mirror every release to Maven Central under `dev.pumpkinlib`.**

Rejected alternatives, with reasons:

* **GitHub Packages — disqualified, not a judgment call.** GitHub's Maven registry requires an access token *even for public packages*; only `ghcr.io` (containers) allows anonymous read. GradleRIO consumers have no way to authenticate. Corroborated negatively: not one major FRC vendordep uses it.
* **JitPack — viable but wrong for us.** DogLog proves it works (`"mavenUrls": ["https://jitpack.io"]`, `com.github.jonahsnider:doglog`). Three reasons we don't: (a) JitPack forces coordinates `com.github.<user>:<repo>`, permanently welding a personal GitHub account into every consumer's build; (b) we publish 18 artifacts from a multi-project build, which JitPack handles awkwardly; (c) JitPack *rebuilds* the source in its own environment — we want to ship the exact jars CI built and tested.
* **Self-hosted Nexus (PhotonVision's approach) — rejected.** Requires a server, a domain, TLS renewal, and someone to notice when it's down. That is a succession liability for a library whose whole pitch includes "will still work when the maintainer graduates."

Why **Maven Central as a mirror** — this is the differentiating decision and no FRC library does it: Central artifacts are immutable and permanent. If the GitHub org is deleted, the account is banned, or Pages changes policy, `https://repo1.maven.org/maven2` still resolves `dev.pumpkinlib:pumpkinlib-core:2027.1.0` and every existing team's build keeps working. That is the concrete, technical form of the succession plan the community explicitly asks for (`web-ecosystem.json`: *"Projects like MapleSim are waning in support as the developer has graduated and if you don't have a succession plan this may be more of a hindrance…"*).

Publishing config:

```groovy
// buildSrc/src/main/groovy/pumpkin.publish-conventions.gradle
apply plugin: 'maven-publish'
apply plugin: 'signing'

publishing {
  publications {
    maven(MavenPublication) {
      groupId = 'dev.pumpkinlib'
      artifactId = project.name          // pumpkinlib-core, etc.
      version = rootProject.pumpkinVersion
      from components.java
      pom {
        name = project.name
        description = 'PumpkinLib — one-stop FRC library'
        url = 'https://pumpkinlib.dev'
        licenses { license { name = 'BSD-3-Clause'; url = 'https://opensource.org/license/bsd-3-clause' } }
        developers { /* >= 2 named maintainers — required by our own support contract */ }
        scm { url = 'https://github.com/pumpkinlib/PumpkinLib' }
      }
    }
  }
  repositories {
    // 1) static repo checked into the gh-pages worktree, published by CI
    maven { name = 'pages';   url = "${rootDir}/build/pages/repo" }
    // 2) Maven Central portal (release builds only, CI-signed)
    maven {
      name = 'central'
      url  = 'https://central.sonatype.com/api/v1/publisher/upload'
      credentials(PasswordCredentials)
    }
  }
}
```

CI on a `v*` tag: `./gradlew publishAllPublicationsToPagesRepository`, then commit `build/pages/` to the `gh-pages` branch (which also carries `vendordep/` and `docs/`), then `publishAllPublicationsToCentralRepository`. One tag → Maven repo + vendordep JSONs + docs all updated atomically.

**License: BSD-3-Clause.** MIT or BSD only. LGPL (YAMS' license) is a real deterrent for teams and creates linking questions no high-school mentor should have to answer. We must also never hard-link LGPL code into a distributed jar.

### 4.4 GradleRIO consumption

A team's robot project consumes PumpkinLib exactly like any other vendordep — no `build.gradle` edits at all for the core path:

* **VS Code:** `WPILib: Manage Vendor Libraries` → `Install new libraries (online)` → paste `https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json`.
* **CLI:** `./gradlew vendordep --url=https://pumpkinlib.dev/vendordep/2026/PumpkinLib.json`
* **Offline:** drop the JSON into `vendordeps/` and run `./gradlew vendordep --url=FRCLOCAL/PumpkinLib.json`.

Two things a vendordep **cannot** do, which we must handle explicitly:

1. **It cannot add an annotation processor.** If a team wants `pumpkinlib-advantagekit`, their `build.gradle` needs `annotationProcessor "org.littletonrobotics.akit:akit-autolog:$version"`. We therefore **never use `@AutoLog` inside PumpkinLib**; our AdvantageKit adapter hand-writes `toLog()`/`fromLog()`. This removes a whole class of "installed the vendordep and nothing logs" support tickets.
2. **It cannot add a Gradle plugin.** `gversion` (for `BuildConstants`) and our `pumpkinCheckDeploy` task require build-script lines. Solution: they live in the **template** (§6), and `PumpkinLib.json`'s `vendor-json-repo` metadata carries an `instructions` URL pointing at a one-screen "add these 8 lines" page. `DeployInfo` degrades gracefully — if `BuildConstants` is absent it reports `UNKNOWN` and raises an `INFO` / `PIT_ONLY` alert rather than failing to compile (it locates the class reflectively; see §11).

### 4.5 Listing in the official WPILib vendor picker

To appear in `WPILib: Manage Vendor Libraries → Install new libraries (online)`, open a PR against `wpilibsuite/vendor-json-repo` adding, in the year's bundle directory:

1. `2026/PumpkinLib-2026.1.0.json` — the vendordep file itself (parent directory must match the year value for `wpilibYear` ≥ 2026).
2. An entry in `2026.json` (the manifest):
```json
{
  "path": "2026/PumpkinLib-2026.1.0.json",
  "name": "PumpkinLib",
  "version": "2026.1.0",
  "uuid": "7b3f0a52-1c4e-4a9d-9f2b-6d0c8e1a4f31",
  "description": "One-stop FRC library: mechanisms, vision, autos, health monitoring, self-test, tuning.",
  "website": "https://pumpkinlib.dev",
  "languages": ["java"],
  "instructions": "https://pumpkinlib.dev/install"
}
```
3. An entry in `2026_metadata.json`: `{ name, uuid, description, website, instructions }`.

`"languages": ["java"]` only — we ship no C++.

### 4.6 The NetworkTables namespace contract

Every PumpkinLib domain publishes under `/Pumpkin/`. This is a *contract*, not a convention: the Elastic and AdvantageScope layouts we ship (§9.4) hard-code these paths, and an ArchUnit/lint rule fails the build on any NT topic string outside it.

```
/Pumpkin/Meta/            Version, GitSha, GitBranch, Dirty, BuildDate, RobotId, WpilibYear
/Pumpkin/Match/           Alliance, IsRed, Station, Event, MatchType, MatchNumber, Replay, FmsAttached, Phase
/Pumpkin/Driver/          Blocking (string[], MAX 3), BlockingMore (string), Ready (boolean)   <- §8.2.1
/Pumpkin/Health/Summary   Worst (string), ActiveCount (int), BlockingCount (int), MatchReady (boolean)
/Pumpkin/Health/<Source>/ Ok, Faults (string[]), LastFaultTime
/Pumpkin/Health/SweepCycles     int    — loops in one full round-robin sweep (§8.3)
/Pumpkin/Health/ExpectedAbsent  string[] — hardware declared absent via .expectAbsent() (§8.3.1)
/Pumpkin/SelfTest/Summary Text block for the pit display
/Pumpkin/SelfTest/<Name>/ Status, Passed, Ran, Detail, DurationSec
/Pumpkin/Power/<Channel>/ Amps, Breaker, OverPct
/Pumpkin/Power/Total      Amps, Watts, MatchJoules, Voltage, MinVoltage
/Pumpkin/Can/<Bus>/       Utilization, BusOff, TxFull, Rec, Tec
/Pumpkin/Loop/            PeriodMs, Max20sMs, Overruns, /Domain/<name>Ms
/Pumpkin/Mechanisms/…     domain 02
/Pumpkin/Drive/…          domain 03
/Pumpkin/Vision/…         domain 04
/Pumpkin/Auto/…           domain 05
/Pumpkin/Tuning/…         domain 08 (mirrors AdvantageScope's expected /Tuning root via an alias)
/Pumpkin/Controls/        Markdown control map (string), for the Elastic text widget
```

WPILib's `Alert` publishes to `/SmartDashboard/<Group>` — an NT3-era path that will read oddly after SmartDashboard's 2027 removal, but it is where Elastic and AdvantageScope look for the Alerts widget. We therefore do **both**: `PumpkinAlert` drives a real WPILib `Alert` (so the stock widget works) *and* mirrors a structured summary to `/Pumpkin/Health/`. If the Alert NT path moves in 2027, only `org.pumpkinlib.core.alert` changes. **[UNVERIFIED]** whether the `/SmartDashboard/<Group>` alert path is retained in WPILib 2027 — re-check at 2027 kickoff.

The sink SPI that domain 07 implements:

```java
package org.pumpkinlib.core.nt;

/** Pluggable telemetry backend. NT4 sink ships in core; AdvantageKit/DogLog/Epilogue sinks are adapters. */
public interface TelemetrySink {
  void publish(String key, double value, String unit);
  void publish(String key, boolean value);
  void publish(String key, String value);
  void publish(String key, double[] value, String unit);
  <T> void publishStruct(String key, edu.wpi.first.util.struct.Struct<T> struct, T value);
  /** Called before the underlying logger starts. Must be a no-op after start. */
  void metadata(String key, String value);
  void flush();
}
```

### 4.7 Distribution smoke test (CI, every release)

Adoption dies at install, not at evaluation (`web-smallteam.json`: *"Cannot Build Robot Code since this is not a WPILib project"*). CI therefore runs, on Windows / macOS / Ubuntu:

1. Download the released `PumpkinTemplate` zip, extract with the platform's **default** unzip tool (this is exactly what broke AdvantageKit on macOS).
2. `./gradlew build` — must pass.
3. `./gradlew simulateJava --tests headless` via `pumpkinlib-testkit`'s headless harness — must reach 500 loop iterations with no `Severity.ERROR` alert, and must pass `AlertBudgetTest` (§8.2.2): at most five `BLOCKS_MATCH` alerts with no hardware present.
4. `./gradlew vendordep --url=https://pumpkinlib.dev/vendordep/<year>/PumpkinLib.json` against a *bare* WPILib example project, then build.
5. `check.py`-equivalent validation of every published vendordep JSON.

A release that fails any step does not ship.

### 4.8 Documentation is a release blocker

Every code snippet in the docs is **extracted at build time from a compiled, executed, asserted test file**. No hand-written code in prose, ever. CI fails if a snippet's source test does not compile against the pinned WPILib/vendor versions. Rationale is direct evidence: a competing library shipped an AI-written doc that said feedforward uses `sin` where it must be `cos`, and a reviewer's conclusion was *"So now I don't even know if I can trust this library to accurately control my mechanism."* One wrong trig identity costs more trust than ten missing pages. We also publish `llms.txt` and `.md` mirrors of every page, because LLMs are now a primary onboarding tool for small teams and hallucinate badly against fast-moving FRC APIs.

---

## 5. Versioning and the 2027 Migration

This is a first-class requirement. WPILib 2027 renames every Java package `edu.wpi.first.*` → `org.wpilib.*`, removes NT3, moves to SystemCore, requires Java 25, deletes Shuffleboard/SmartDashboard/PathWeaver/RobotBuilder, deletes a long list of HAL features, and changes the field coordinate origin. Peter Johnson (WPILib lead) states plainly that *"it will not be possible to have the same code target both the 2026 and 2027 libraries."* Design that does not plan for this dies in January 2027.

### 5.1 Versioning scheme: year-anchored `YEAR.MAJOR.MINOR`

```
2026.1.0        first 2026-targeting release
2026.1.3        bugfix
2027.0.0-beta.2 pre-kickoff, tracking WPILib 2027 alphas
2027.1.0        2027 kickoff GA
```

* `YEAR` = the WPILib season the artifact targets. Reading a version tells you which WPILib it runs on with zero lookup. Matches PathPlanner (`2026.1.2`) and YAGSL (`2026.8.05`); rejects Phoenix's `26.x` because two digits are ambiguous.
* `MAJOR` bumps only on a breaking change to PumpkinLib's own public API. **Frozen between kickoff and championship** — a public, written commitment, because mentors explicitly compare third-party libs unfavorably to WPILib's per-season API freeze.
* `MINOR` = additive features and fixes.
* Pre-release suffix `-beta.N` / `-rc.N` while the target WPILib is in alpha.
* The Maven `version` string, the vendordep `version`, and the git tag are always identical.

Support contract published on page 1 of the docs: **no breaking changes between kickoff and championship; semver within a year line; one full season of deprecation before removal; ≥2 named maintainers; a public matrix of the exact WPILib and vendor versions each release was tested against.**

### 5.2 Branch strategy

```
main                 → the NEXT season. Today (Aug 2026) that is 2027, tracking WPILib 2027 alphas.
release/2026         → maintenance only. Bugfixes cherry-picked from main. EOL at 2027 kickoff.
release/2027         → cut at 2027 kickoff, then main moves to 2028.
```

One repo. One canonical source tree. **Not** a fork per year — forks diverge, and a bug fixed in 2027 must land in 2026 while 2026 is still supported.

Because today's robots are on 2026 and the user's two teams are on 2026, **v0.1 ships against WPILib 2026 for offseason use**, with the seam in place from the first commit. `2027.0.0-beta` follows within weeks of the first WPILib 2027 beta. `2026.x` is explicitly labeled "offseason / last-season support" and is EOL'd at 2027 kickoff.

### 5.3 The seam — what actually makes the rename mechanical

The naive answers are both wrong. Wrapping every WPILib type is a huge surface, violates "never hide WPILib," and produces exactly the over-abstraction the community punished YASS for. Doing nothing means editing 400 files in January.

The correct seam has **four parts**, and it works because the rename itself is a *pure textual substitution on imports*. What makes it non-mechanical is only (a) types that move subpackage, (b) methods renamed or removed, (c) semantic changes. So we shrink (a)/(b)/(c) to a countable list and automate the rest.

#### Part A — A banned-API list enforced in CI

`pumpkin-core`'s test source set contains an ArchUnit suite that is a release blocker:

```java
package org.pumpkinlib.arch;

import com.tngtech.archunit.junit.*;
import com.tngtech.archunit.lang.ArchRule;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

@AnalyzeClasses(packages = "org.pumpkinlib", importOptions = ImportOption.DoNotIncludeTests.class)
public final class WpiSurfaceTest {

  /** Removed in 2027. Referencing any of these guarantees a rewrite. */
  @ArchTest static final ArchRule noDeadWpiApis = noClasses().should().dependOnClassesThat()
      .resideInAnyPackage(
          "edu.wpi.first.wpilibj.smartdashboard..",
          "edu.wpi.first.wpilibj.shuffleboard..",
          "edu.wpi.first.cameraserver..")
      .orShould().dependOnClassesThat().haveNameMatching(
          ".*\\.(Relay|AnalogOutput|AnalogGyro|AnalogTrigger|SPI|DMA|Counter|Ultrasonic|"
        + "DigitalGlitchFilter|Servo|NidecBrushless|ADIS16448_IMU|ADIS16470_IMU|ADXRS450_Gyro|ADXL345_SPI)");

  /**
   * Mutable units are deleted in 2027 — they must never cross a PumpkinLib boundary.
   * ONE predicate, matched on the fully-qualified name. Deliberately NOT
   * `resideInAPackage("edu.wpi.first.units.measure..").andShould().haveSimpleNameStartingWith("Mut")`:
   * that is a conjunction on a `noClasses()` predicate, so it only forbids classes satisfying
   * BOTH clauses. It happens to work today because MutDistance/MutAngle live in that package —
   * but it reads as a ban on the whole `measure` package (which every config record depends on),
   * and one upstream package move turns the accident into a build-breaking lie.
   */
  @ArchTest static final ArchRule noMutableUnits = noClasses().should()
      .dependOnClassesThat().haveNameMatching("edu\\.wpi\\.first\\.units\\.measure\\.Mut.*");

  /**
   * Volatile WPILib API is confined to the compat seam. These five types are the ones whose
   * package, class name, or method set we expect to move in 2027, so exactly TWO packages in
   * the whole library are allowed to name them:
   *   - org.pumpkinlib.core.compat  (Clock, Platform, PumpkinField, Gamepads, MathX, PoseX)
   *   - org.pumpkinlib.core.match   (MatchContext — the single DriverStation reader, §10)
   * Everything else calls a facade. This rule is GREEN on the library as designed; §5.3-E is
   * the call-site conversion table that makes it green, and it is a release blocker.
   */
  @ArchTest static final ArchRule volatileApiIsConfined = classes()
      .that().resideOutsideOfPackages(
          "org.pumpkinlib.core.compat..",
          "org.pumpkinlib.core.match..")          // <- the ONLY allowlist entry beyond compat
      .should().onlyDependOnClassesThat().haveNameNotMatching(
          "edu\\.wpi\\.first\\.wpilibj\\.(Timer|RobotController|RobotBase|Filesystem|DriverStation)");

  /**
   * DriverStation has exactly ONE reader in the whole library: org.pumpkinlib.core.match.
   * Note this list is narrower than volatileApiIsConfined's — `compat` is NOT allowed to name
   * DriverStation either. That is why Platform has no diagnosticsMode(): it would have made
   * compat a second DriverStation reader, and a "single reader" rule with two readers is a lie.
   * MatchContext.isDiagnostics() is the one spelling (§10).
   *
   * If a second package ever needs DriverStation, the answer is a new MatchContext method,
   * not a new allowlist entry.
   */
  @ArchTest static final ArchRule onlyMatchReadsDriverStation = classes()
      .that().resideOutsideOfPackages("org.pumpkinlib.core.match..")
      .should().onlyDependOnClassesThat()
      .haveNameNotMatching("edu\\.wpi\\.first\\.wpilibj\\.DriverStation.*");

  /** Users must never be forced to extend a PumpkinLib class. Commands v3 depends on this. */
  @ArchTest static final ArchRule noMandatoryInheritance = noClasses()
      .that().resideInAPackage("org.pumpkinlib..")
      .should().beAssignableTo("edu.wpi.first.wpilibj2.command.SubsystemBase");
}
```

`noMandatoryInheritance` matters most: 135's Consul requires `extends SubsystemChecker` and is therefore hostile to Commands v3, which uses `Mechanism` as its noun and composition as its model. **PumpkinLib requires no inheritance anywhere.** Everything is an interface plus a registry.

`volatileApiIsConfined` matters second-most, and it is the rule that most easily ships broken. A rule that is red on day one gets `@Disabled` in week two and the 2027 seam becomes decorative. So we do not weaken the rule — we fix the call sites, once, and enumerate them in §5.3 Part E so no domain author has to guess.

#### Part B — `org.pumpkinlib.core.compat`, the only package that knows the year

The complete list of known 2027 deltas that are *not* a pure import rename, each with its façade. This list is small on purpose.

```java
package org.pumpkinlib.core.compat;

import edu.wpi.first.units.measure.Frequency;
import edu.wpi.first.units.measure.Time;
import static edu.wpi.first.units.Units.Hertz;
import static edu.wpi.first.units.Units.Seconds;

/**
 * The single place PumpkinLib reads the robot clock or the loop period.
 * Never Timer.getFPGATimestamp(). Never System.nanoTime(). Never a wall clock for RATE GATING —
 * see periodCycles() and §8.3: time is for measuring, cycles are for scheduling.
 */
public final class Clock {
  private Clock() {}

  /** Replay-safe monotonic seconds. Maps to Timer.getTimestamp(). For MEASURING durations. */
  public static double seconds() { return edu.wpi.first.wpilibj.Timer.getTimestamp(); }
  public static Time now() { return Seconds.of(seconds()); }

  /** Loop period, captured once from TimedRobot.getPeriod() at PumpkinRobot construction. */
  public static Time period();
  public static double periodSeconds();

  /** Monotonic robot-loop counter. Increments exactly once per periodic(), from 0. */
  public static long cycle();

  /**
   * How many robot loops make up one period of {@code hz}, minimum 1.
   * {@code periodCycles(Hertz.of(4))} == 13 at 50 Hz (ceil(50/4)).
   * THIS is how every periodic rate in PumpkinLib is expressed. It is exact under replay,
   * under a 100 Hz robot, and under sim stepping — a wall-clock gate is none of those.
   */
  public static int periodCycles(Frequency hz) {
    return Math.max(1, (int) Math.ceil(1.0 / (hz.in(Hertz) * periodSeconds())));
  }

  /** Convert a duration to a whole number of loops, rounded up, minimum 1. Used by debounce(). */
  public static int cyclesFor(Time duration) {
    return Math.max(1, (int) Math.ceil(duration.in(Seconds) / periodSeconds()));
  }

  /** True on exactly one loop out of every n, with a stable phase offset derived from the key. */
  public static boolean everyNCycles(int n, String phaseKey) {
    return n <= 1 || (cycle() + Math.floorMod(phaseKey.hashCode(), n)) % n == 0;
  }
}
```

**Why cycles and not seconds.** Under AdvantageKit replay `Timer.getTimestamp()` *is* the log timestamp, so a seconds-based gate is deterministic there. It is not deterministic under DogLog, under raw NT4, under `SimHooks.stepTiming`, or in a JUnit test that ticks the scheduler by hand — and PumpkinLib supports all four. A cycle counter is deterministic under every backend we ship, costs one `long` increment, and makes `PumpkinReplayVerify` diffs meaningful instead of noisy. `everyNCycles` phase-offsets by key so that ten 4 Hz consumers do not all land on cycle 0 and rebuild the very spike we are removing.

```java
package org.pumpkinlib.core.compat;

import java.nio.file.Path;
import java.util.Optional;

/** Platform facts whose source changes with roboRIO → SystemCore. */
public final class Platform {
  private Platform() {}

  public static boolean isSimulation()  { return edu.wpi.first.wpilibj.RobotBase.isSimulation(); }
  public static boolean isReal()        { return edu.wpi.first.wpilibj.RobotBase.isReal(); }
  public static int     teamNumber()    { return edu.wpi.first.wpilibj.RobotController.getTeamNumber(); }

  /** roboRIO serial. On SystemCore availability is UNVERIFIED — returns empty if unavailable. */
  public static Optional<String> serialNumber() {
    String s = edu.wpi.first.wpilibj.RobotController.getSerialNumber();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /** roboRIO web-dashboard "comments" field. UNVERIFIED on SystemCore. */
  public static Optional<String> comments() {
    String s = edu.wpi.first.wpilibj.RobotController.getComments();
    return (s == null || s.isBlank()) ? Optional.empty() : Optional.of(s.trim());
  }

  /** Writable, redeploy-surviving directory. /home/lvuser on roboRIO. */
  public static Path persistentDir() {
    return edu.wpi.first.wpilibj.Filesystem.getOperatingDirectory().toPath();
  }

  public static Path deployDir() {
    return edu.wpi.first.wpilibj.Filesystem.getDeployDirectory().toPath();
  }

  // NOTE: there is deliberately NO Platform.diagnosticsMode() here. Test/Utility mode is a
  // DriverStation read, and DriverStation has exactly one reader in this library —
  // org.pumpkinlib.core.match. Callers say MatchContext.isDiagnostics() (§10). Putting it here
  // would have made `compat` a second DriverStation reader and quietly broken the one rule
  // (onlyMatchReadsDriverStation) that keeps the 2027 DS surface to a single file.

  // ---- RobotController surface used by the built-in monitors (§8.5). ----
  // These exist so CanBusMonitor / RailMonitor / BrownoutMonitor / BatteryMonitor can be written
  // in org.pumpkinlib.core.health.builtin without naming RobotController, which the ArchUnit
  // rule forbids outside this package.

  /** RobotController.getCANStatus() -> edu.wpi.first.hal.can.CANStatus, flattened. */
  public static CanStatus canStatus() {
    var s = edu.wpi.first.wpilibj.RobotController.getCANStatus();
    return new CanStatus(s.percentBusUtilization, s.busOffCount, s.txFullCount,
                         s.receiveErrorCount, s.transmitErrorCount);
  }

  /** Flattened CAN status. Same shape as Phoenix 6's CANBusStatus, so one monitor reads both. */
  public record CanStatus(double utilization, int busOff, int txFull, int rec, int tec) {}

  public static double batteryVolts()  { return edu.wpi.first.wpilibj.RobotController.getBatteryVoltage(); }
  public static boolean isBrownedOut() { return edu.wpi.first.wpilibj.RobotController.isBrownedOut(); }
  public static int faultCount5V()     { return edu.wpi.first.wpilibj.RobotController.getFaultCount5V(); }
  public static int faultCount3V3()    { return edu.wpi.first.wpilibj.RobotController.getFaultCount3V3(); }
  public static int faultCount6V()     { return edu.wpi.first.wpilibj.RobotController.getFaultCount6V(); }
}
```

`Platform.canStatus()` returning a flattened record rather than the HAL type is deliberate: `edu.wpi.first.hal.can.CANStatus` is a HAL type on the 2027 move list (`edu.wpi.first.hal.` → `org.wpilib.hardware.hal.`, §5.3 Part C), and it is the *only* HAL type PumpkinLib would otherwise touch. Flattening it here means `pumpkin-core` has zero HAL imports outside `compat`.

```java
package org.pumpkinlib.core.compat;

import edu.wpi.first.math.geometry.Pose2d;
import edu.wpi.first.math.geometry.Rotation2d;
import edu.wpi.first.math.geometry.Translation2d;
import edu.wpi.first.units.measure.Distance;

/**
 * Every field-relative transform in PumpkinLib goes through here.
 * 2026: origin at the blue-alliance wall corner, +X toward red.
 * 2027: origin at FIELD CENTER, scoring table at top — a 180 degree rotation from 2026.
 * Under center-origin, alliance flipping collapses to a negation. That change is ONE file.
 */
public final class PumpkinField {
  private PumpkinField() {}

  public static Distance length();
  public static Distance width();
  public static Translation2d center();

  /** Mirror a pose to the other alliance. Correct under both origin conventions. */
  public static Pose2d flip(Pose2d bluePose);
  public static Translation2d flip(Translation2d blue);
  public static Rotation2d flip(Rotation2d blue);

  /** Flip only when the latched alliance is red. THE supplier PathPlanner/Choreo should get. */
  public static Pose2d forAlliance(Pose2d bluePose);
}
```

Also in `compat`: `Gamepads` (2027 collapses `XboxController`/`PS4Controller`/`PS5Controller`/`StadiaController` into one `Gamepad`), `MathX.clamp` (2027: `MathUtil.clamp` → `Math.clamp`), and `PoseX.expTwist(pose, twist)` (2027: `Pose2d.exp(Twist)` → `pose.plus(twist.exp())`).

That is the **entire** hand-maintained surface: 6 small classes.

#### Part C — Generated 2027 sources, with the direction flipping at kickoff

Until 2027 kickoff:

* Canonical source is written against **2026 names** (`edu.wpi.first.*`, Java 17 syntax).
* The `wpilib2027` Gradle variant applies a **checked-in, reviewed substitution map** into `build/generated/sources/wpi2027/`, then compiles that against WPILib 2027 with `--release 25`.
* Files that cannot be mechanically translated are *not* translated: `src/wpi2026/java/` and `src/wpi2027/java/` overlays carry the `compat` package's two implementations, and the generator skips them.

```groovy
// buildSrc/.../pumpkin.wpi-variant.gradle  (abridged, real shape)

// ---------------------------------------------------------------------------
// 1) Load the rename map ONCE, at configuration time, into a deterministic,
//    longest-key-first map. Three separate bugs are being avoided here:
//      * Properties is a Hashtable -> iteration order is UNSPECIFIED. With overlapping
//        prefixes ("edu.wpi.first.hal." and a future "edu.wpi.first.") the shorter key can
//        win and produce "org.wpilib.hal." on one machine and "org.wpilib.hardware.hal."
//        on another. Longest-first ordering makes the result independent of load order.
//      * Re-parsing the file inside filter{} is O(lines x filesize) — minutes on a 40k-line
//        tree — and newReader() without withReader() leaks one file descriptor PER LINE.
//      * The comparator must never return 0 for unequal keys or TreeMap silently drops them.
// ---------------------------------------------------------------------------
def renameMapFile = file("$rootDir/gradle/wpi-rename-2027.properties")   // reviewed, in git
def renameMap = new TreeMap<String, String>({ a, b ->
  (b.length() <=> a.length()) ?: (a <=> b)          // longest key first, then lexicographic
} as Comparator)
renameMapFile.withReader('UTF-8') { r ->
  def p = new Properties()
  p.load(r)
  p.each { k, v -> renameMap.put(k.toString().trim(), v.toString().trim()) }
}

def outDir = layout.buildDirectory.dir("generated/sources/wpi2027")

// Only import/package lines are rewritten. Applying replace() to the whole line rewrites
// string literals, comments, @ReplayExempt reasons, NT topic names, log keys, and the fenced
// Java snippets Principle 11 extracts from tests — all of which must survive verbatim.
def IMPORT_OR_PACKAGE = ~/^\s*(import|package)\s/

tasks.register('generate2027Sources', Copy) {
  inputs.file(renameMapFile)                        // rerun when the map changes
  from("src/main/java")
  into(outDir)
  filter { String line ->
    if (!IMPORT_OR_PACKAGE.matcher(line).find()) return line
    renameMap.inject(line) { acc, k, v -> acc.replace(k, v) }
  }

  // 2) Fail loudly on anything the map missed, rather than shipping a half-renamed tree.
  //    src/wpi2027/java is the hand-written 2027 overlay and is not generated, so it is
  //    outside this check by construction (different directory).
  doLast {
    def bad = []
    fileTree(outDir).matching { include '**/*.java' }.each { f ->
      f.readLines().eachWithIndex { l, i ->
        if (l.contains('edu.wpi.first')) bad << "${f}:${i + 1}: ${l.trim()}"
      }
    }
    if (bad) {
      throw new GradleException(
        "generate2027Sources: ${bad.size()} un-renamed edu.wpi.first reference(s) survived the\n" +
        "rename map. Add the missing prefix to gradle/wpi-rename-2027.properties:\n  " +
        bad.take(20).join("\n  "))
    }
  }
}

sourceSets {
  wpi2027 {
    java.srcDirs = [ outDir, "src/wpi2027/java" ]
    compileClasspath += configurations.wpilib2027
  }
}

// 3) Two toolchains, explicitly. `options.release = 25` requires a JDK >= 25 COMPILER;
//    it is not a cross-compile flag you can pass to javac 17. Gradle's toolchain service
//    provisions both, and CI must have both available (see below).
compileJava {
  javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(17) }
  options.release = 17
}
compileWpi2027Java {
  dependsOn generate2027Sources
  javaCompiler = javaToolchains.compilerFor { languageVersion = JavaLanguageVersion.of(25) }
  options.release = 25
}
```

**CI needs both JDKs.** The 2026 line compiles on JDK 17 (GradleRIO 2026 pins it); the 2027 variant compiles on JDK 25 (SystemCore's runtime). The dual-compile job therefore runs:

```yaml
# .github/workflows/ci.yml  (excerpt)
- uses: actions/setup-java@v4
  with:
    distribution: temurin
    java-version: |
      17
      25
- run: ./gradlew build compileWpi2027Java
```

and `gradle.properties` sets `org.gradle.java.installations.auto-download=true` so a contributor with only JDK 17 installed still gets a working `compileWpi2027Java` instead of a confusing toolchain error. A contributor with no network gets a named failure: the toolchain resolver's message is one of the few Gradle errors that already says exactly what is missing.

`gradle/wpi-rename-2027.properties` (excerpt — every entry reviewed by a human, none guessed):

```properties
edu.wpi.first.wpilibj.        = org.wpilib.wpilibj.
edu.wpi.first.wpilibj2.command. = org.wpilib.wpilibj2.command.
edu.wpi.first.math.           = org.wpilib.math.
edu.wpi.first.units.          = org.wpilib.units.
edu.wpi.first.networktables.  = org.wpilib.networktables.
edu.wpi.first.util.           = org.wpilib.util.
edu.wpi.first.net.            = org.wpilib.net.
edu.wpi.first.hal.            = org.wpilib.hardware.hal.
```

**[UNVERIFIED]** — the exact 2027 target subpackage for each `edu.wpi.first.*` root. AdvantageKit's main branch already imports `org.wpilib.math.geometry.Rotation2d` (confirmed), and allwpilib's own 2027 test sources show `HAL` at `org.wpilib.hardware.hal.HAL` (per dossier `web-simtest.json`, from a tag diff). Every row of this map must be re-verified against the WPILib 2027 beta before `2027.0.0-beta.1`, and CI's compile-against-2027 job is what catches a wrong row.

**CI runs both compiles on every PR.** A change that compiles on 2026 but not 2027 fails the build the day it is written, not in January.

**At 2027 kickoff the generator is deleted.** Canonical source becomes 2027 (`org.wpilib`), `release/2026` freezes at its last release, and `src/wpi2026/` disappears. The generator is a temporary bridge, not permanent infrastructure — that is what keeps it from rotting.

#### Part D — Behavioral guardrails that make the rename *safe*, not just compilable

* **No `Timer.getFPGATimestamp()` anywhere.** All time via `compat.Clock`. This is also what makes PumpkinLib replay-safe under AdvantageKit.
* **No 20 ms assumption.** AdvantageKit supports only 50 Hz robots and maple-sim forbids overriding sim timings when AdvantageKit is present; we neither require nor silently change the loop period, and every periodic rate in this domain is expressed in Hz and re-derived from the actual period.
* **CAN devices and DIO only.** No relay, analog, SPI, DMA, counter, servo, ultrasonic, interrupts — all removed on SystemCore.
* **Multi-CAN-bus aware from day one.** SystemCore has multiple buses. `CanBusMonitor` takes a list of named buses, not a singleton (§8.5).
* **Immutable `Measure` at boundaries, raw doubles in the hot loop.** WPILib's own rule: accept `Measure`, return concrete immutable types (`Distance`, never `MutDistance`), use `.in(Unit)` only when bridging to non-units APIs. Config is construction-time, so allocation is irrelevant there; we convert to base-unit doubles once in the constructor.
* **No Commands v2 inheritance requirement, and no Commands v3 dependency.** Behavior is exposed as `Supplier<Command>` factories and `Trigger`s. A v3 adapter is additive.

#### Part E — The call-site conversion table (release blocker, cross-domain)

`volatileApiIsConfined` is only worth shipping if the library is green under it on the first commit. As written across the other design docs it is **not** — six call sites name a confined type directly. Each one has an existing facade; none needs a new concept. This table is normative for the domains named, and P0 does not close until every row is done.

| Doc / section | Today (fails the rule) | Required call | Facade owner |
|---|---|---|---|
| 01 §3.9 | `RobotBase.isSimulation()` | `Platform.isSimulation()` | `core.compat.Platform` |
| 01 §5.3 `Gains.realOrSim` | `RobotBase.isReal()` | `Platform.isReal()` | `core.compat.Platform` |
| 01 §6.2 | `DriverStation.isDisabled()` | `MatchContext.isDisabled()` | `core.match.MatchContext` |
| 02 §5.2 | `DriverStation.isFMSAttached()` | `MatchContext.isFMSAttached()` | `core.match.MatchContext` |
| 02 §11.2 | `Filesystem.getOperatingDirectory()` / `getDeployDirectory()` | `Platform.persistentDir()` / `Platform.deployDir()` | `core.compat.Platform` |
| 05 §6.2 | `Timer.getFPGATimestamp()` / `Timer.getTimestamp()` | `Clock.seconds()` | `core.compat.Clock` |

**Within this domain**, the same conversion applies, and every row below is already reflected in the
code in §7–§11. It is tabulated rather than left in prose because "already reflected" is exactly the
claim that rots:

| This doc / section | Confined API | Required call | Facade owner |
|---|---|---|---|
| §8.5 `CanBusMonitor.rio()` | `RobotController.getCANStatus()` | `Platform.canStatus()` | `core.compat.Platform` |
| §8.5 `RailMonitor` | `RobotController.getFaultCount5V()` etc. | `Platform.faultCount5V()` / `…3V3()` / `…6V()` | `core.compat.Platform` |
| §8.5 `BrownoutMonitor` | `RobotController.isBrownedOut()` | `Platform.isBrownedOut()` | `core.compat.Platform` |
| §8.5 `BatteryMonitor` | `RobotController.getBatteryVoltage()` | `Platform.batteryVolts()` | `core.compat.Platform` |
| §8.5 / §9.5 `DsMonitor` | `DriverStation.getJoystickName(port)` | `MatchContext.joystickName(port)` | `core.match.MatchContext` |
| §8.4 `SelfTest.runAll()` gate | `DriverStation.isTest()` | `MatchContext.isDiagnostics()` | `core.match.MatchContext` |
| §7.2 `assignCommand`, §11.2 `takeCommand` | `DriverStation.isTest()` | `MatchContext.isDiagnostics()` | `core.match.MatchContext` |
| §9.3 default LED state table | `DriverStation::isEStopped` | `MatchContext::isEStopped` | `core.match.MatchContext` |
| §9.4 `PumpkinDashboard.init()` | `Filesystem.getDeployDirectory()` | `Platform.deployDir()` | `core.compat.Platform` |
| §7.1 identity chain | `Filesystem.getOperatingDirectory()` | `Platform.persistentDir()` | `core.compat.Platform` |
| §8.5 `BrownoutMonitor` timestamp | `Timer.getFPGATimestamp()` | `Clock.seconds()` | `core.compat.Clock` |

Every `MatchContext` method named in either table exists in §10's API listing. That is not a
coincidence — §10 was written *from* these two tables, and `AlertBudgetTest`'s sibling
`WpiSurfaceTest` is what keeps them honest.

Two rules that keep this from re-rotting:

1. **A new confined-API need is a new facade method, never a new allowlist entry.** There are exactly two allowlists and they are deliberately different widths: `volatileApiIsConfined` allows `core.compat` + `core.match`; `onlyMatchReadsDriverStation` allows `core.match` **only**. Both are frozen. If domain 04 needs `DriverStation.getAlliance()`, the answer is that `MatchContext.alliance()` already exists and is *better* (it latches).
2. **The facade is not a wrapper tax.** Every method above is a one-line delegation that the JIT inlines to nothing. The escape hatch survives: nothing stops team code from calling `DriverStation` directly — the rule is scoped to `org.pumpkinlib..`, not to `frc.robot..`. We never hide WPILib from users; we only stop *ourselves* from scattering 2027's rename across 400 files.

---

## 6. Companion Template Repo

### 6.1 The decision, and the argument against it, head-on

**PumpkinLib ships as library + template + CLI. The library is the product; the template is the installer; the CLI generates the code teams should own.**

The reviewer objection is real and evidence-backed. The strongest signal in the small-team research is that **code generation beat runtime abstraction**: a working CSA said of YAMGen, *"the codegen route is far more useful than YAMS, and comes with fewer footguns,"* and *"The fact that it doesn't use the rest of YAMS is a strength, not a weakness."* CTRE independently shipped `corvus` (JSON → mechanism code) and Tuner X generators. Generated code is debuggable by a stranger at an event, survives the library's abandonment, and does not break when the generator does.

That argument is correct — **for the mechanism layer**. It is wrong for the platform layer, for three reasons:

1. **Cross-cutting infrastructure must be centrally fixable.** A CAN-utilization monitor, a battery-sag detector, an alert registry, and a self-test sequencer are identical on every robot and get *better* over time. Generating 300 copies means 300 divergent copies and zero bug-fix propagation. That is precisely the failure the ecosystem already exhibits: 3061's `FaultReporter` is locked inside a swerve starter repo, and **ElasticLib is distributed as a file you copy into `util/`, so every team runs a divergent private copy and fixes never propagate.** We will not add a 301st copy.
2. **Template-only distribution is a documented onboarding failure.** AdvantageKit's zip-template model produced *"I don't get how the template projects work…"* and *"Cannot Build Robot Code since this is not a WPILib project"* from multiple users on the kickoff thread. And AdvantageKit's own docs say *"Manually updating projects to 2026 is not recommended due to the risk of subtle breaking changes"* — meaning a template-only library cannot be upgraded mid-season at all. A vendordep can: bump one version string.
3. **The 2027 rename is survivable for a library and brutal for a template.** We can ship `2027.0.0` and a team upgrades by editing one line. A team holding generated 2026-flavored platform code has to hand-migrate all of it.

So the split is by *layer*, and we state it in the README:

| Layer | Delivery | Why |
|---|---|---|
| Platform (this domain), vision fusion, auto glue, tuning | **Library (vendordep)** | Identical everywhere; must be centrally fixable; must survive the rename |
| Mechanism subsystems, `RobotContainer` wiring, constants | **Generated by `pumpkin gen` into the team's repo, with provenance comments** | Team owns it, reads it, edits it, keeps it if PumpkinLib dies |
| Project skeleton, build.gradle, CI, deploy dir, dashboards | **Template repo** | You cannot start from nothing |

### 6.2 What `PumpkinTemplate` contains

A GitHub **template repository** (green "Use this template" button — no zip, no unzip-to-the-right-place step) plus a downloadable zip for offline use. It is a *complete, deployable, simulatable robot project* that builds green on clone with zero edits.

```
PumpkinTemplate/
├── build.gradle                      # GradleRIO + gversion + pumpkinCheckDeploy + spotless + JUnit5
├── settings.gradle
├── .github/workflows/ci.yml          # ./gradlew build test  in wpilib/roborio-cross-ubuntu:<year>-22.04
├── .vscode/tasks.json                # "Deploy (strict)" — the safe deploy is ONE CLICK (§7.4)
├── .wpilib/wpilib_preferences.json   # teamNumber placeholder, projectYear
├── vendordeps/
│   ├── WPILibNewCommands.json
│   ├── PumpkinLib.json
│   ├── PumpkinLib-Phoenix6.json      # `pumpkin init` deletes the vendor sets you don't pick
│   ├── PumpkinLib-REVLib.json
│   ├── PumpkinLib-PhotonVision.json
│   └── PumpkinLib-PathPlanner.json
├── src/main/java/frc/robot/
│   ├── Main.java
│   ├── Robot.java                    # extends PumpkinRobot — ~25 lines, see §13
│   ├── RobotContainer.java           # bindings + subsystem construction
│   ├── BuildConstants.java           # GENERATED by gversion; gitignored
│   ├── robots/
│   │   ├── RobotIds.java             # the RobotIdentity table (§7)
│   │   ├── CompBot.java              # per-robot constant overlay
│   │   ├── PracticeBot.java
│   │   └── SimBot.java
│   └── subsystems/                   # EMPTY. `pumpkin gen mechanism` fills it.
├── src/main/deploy/
│   ├── elastic-layout.json           # ships a working driver dashboard (§9.4)
│   ├── pathplanner/                  # empty PathPlanner project skeleton
│   └── pumpkin/
│       └── config-snapshot.json      # the committed tuned-value snapshot (§11.2)
├── src/test/java/frc/robot/
│   └── SmokeTest.java                # boots headless 500 cycles; no Severity.ERROR, <=5 BLOCKS_MATCH
├── advantagescope/
│   ├── AdvantageScope.json           # debug layout
│   └── AdvantageScopeTuning.json     # tuning layout (3061 ships both; nobody else does)
├── ROBOT.md                          # auto-regenerated: subsystems, CAN map, bindings, robot ids
├── CONTROLS.md                       # auto-generated from ControlMap (§9.5)
└── README.md                         # 10-minute path from clone to a driving robot in sim
```

The `build.gradle` lines a vendordep cannot supply, pre-wired:

```groovy
plugins {
  id "java"
  id "edu.wpi.first.GradleRIO" version "2026.2.1"
  id "com.peterabeles.gversion" version "1.10"
}

// git provenance baked into the deployed jar
project.compileJava.dependsOn(createVersionFile)
gversion {
  srcDir       = "src/main/java/"
  classPackage = "frc.robot"
  className    = "BuildConstants"
  dateFormat   = "yyyy-MM-dd HH:mm:ss z"
  timeZone     = "America/New_York"
  indent       = "  "
}

// refuse to deploy a SIM identity to real hardware; warn (do not block) on a dirty tree
deploy.targets.roborio.artifacts.frcJava.dependsOn 'pumpkinCheckDeploy'

test { useJUnitPlatform() }
```

And the one-click safe path, so the *strict* deploy is reachable from the only UI most students use:

```jsonc
// .vscode/tasks.json  (shipped in PumpkinTemplate)
{
  "version": "2.0.0",
  "tasks": [
    {
      "label": "Deploy (strict)",
      "type": "shell",
      "command": "./gradlew deploy -PstrictDeploy",
      "windows": { "command": ".\\gradlew.bat deploy -PstrictDeploy" },
      "group": "build",
      "problemMatcher": [],
      "detail": "Deploy, but FAIL if the working tree is dirty. Use this for the last deploy before a match."
    },
    {
      "label": "Deploy (dirty, allowed)",
      "type": "shell",
      "command": "./gradlew deploy -PallowDirtyDeploy",
      "windows": { "command": ".\\gradlew.bat deploy -PallowDirtyDeploy" },
      "group": "build",
      "problemMatcher": [],
      "detail": "Deploy uncommitted code to a robot that has already played a match. Use at an event when you must fix the robot NOW."
    }
  ]
}
```

`Ctrl+Shift+P → Tasks: Run Task → Deploy (strict)`, or bind it to a key. The stock WPILib `Deploy Robot Code` command remains the everyday path and always works. The second task exists because `-P` properties are unreachable from the F5 workflow, and the one situation where PumpkinLib *does* block a dirty deploy (§7.4: this robot has been FMS-attached) must still have a one-click escape — otherwise we have rebuilt the bug we just fixed.

`pumpkin init` (the CLI) is the friction-free path: it clones the template, sets the team number, deletes the vendor sets you didn't choose, seeds `RobotIds.java`, and prints the next command. One artifact, one command, smoke-tested in CI on three OSes.

**Support surfaces:** a public, search-indexed Chief Delphi thread + GitHub Discussions are canonical. Docs readable without an account. Discord is supplementary only — it is blocked in at least one active FRC country.

---

## 7. Robot Identity and Multi-Robot Config

### 7.1 The mechanism — and why the obvious answer is wrong

The obvious answer, "put a `robot-id.txt` in the deploy directory," **does not work**: the deploy directory is deployed *from the laptop*, so every robot receives the same file. Identity must live on the robot.

Resolution order (first match wins). Each step exists to cover a failure of the one before it:

1. **`Platform.persistentDir()/pumpkin/robot-id`** — a file on the robot's own filesystem (`/home/lvuser/...` on roboRIO, backed by `Filesystem.getOperatingDirectory()` inside the facade). Survives redeploy; wiped by a reimage. **PumpkinLib writes it for you** from a dashboard command, so nobody ever SSHs into a robot. *Primary, and specifically because it is the only mechanism guaranteed to exist on SystemCore.*
2. **`Platform.comments()`** — the roboRIO web-dashboard "comments" field, matched case-insensitively against registered substrings. Student-editable through a web page, no code, no SSH. **[UNVERIFIED]** on SystemCore, which is exactly why the read is behind the facade and returns `Optional`.
3. **`Platform.serialNumber()`** — exact match against a registered table. Zero setup once recorded; the string is in the log the first time you boot. **[UNVERIFIED]** on SystemCore.
4. **`Platform.isSimulation()`** → `RobotId.SIM`.
5. **Declared fallback**, plus an `ERROR` / `BLOCKS_MATCH` alert naming every strategy that was tried and what it saw. This is one of the five the alert budget (§8.2.2) reserves: a robot running an unknown identity's constants is running the wrong gearing and the wrong limits, and that genuinely means do not take the field.

All five read through `org.pumpkinlib.core.compat.Platform`; `org.pumpkinlib.core.identity` contains no `edu.wpi.first.wpilibj` import at all. When `getComments()` disappears on SystemCore, strategy 2 becomes `Optional.empty()` in one file and the chain degrades to strategy 1 with a named alert — it does not fail to compile.

We do **not** use a DIO jumper: it consumes a channel, it can fall out, and it is the one mechanism the 2027 removal list makes least attractive to bet on.

### 7.2 API

```java
package org.pumpkinlib.core.identity;

/** Teams extend this set by adding enum constants; nothing in PumpkinLib hardcodes the members. */
public enum RobotId { COMP, PRACTICE, PROTO, SIM }
```

```java
package org.pumpkinlib.core.identity;

import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public final class RobotIdentity {

  /** Declarative registration. Call exactly once, before any config is read. */
  public static Builder configure() { return new Builder(); }

  /** Resolved identity. Throws IllegalStateException if configure() was never called. */
  public static RobotId current();

  /** How it was resolved — logged to /Pumpkin/Meta/RobotIdSource and shown in the pit. */
  public static String source();

  /** Pick a value per robot. Missing key -> IllegalArgumentException naming the robot and the caller. */
  public static <T> T pick(Map<RobotId, T> byRobot);

  /** Pick with a base value and per-robot overlays applied on top. */
  public static <T> T overlay(T base, Map<RobotId, Function<T, T>> overrides);

  /** Write the persistent id file on this robot. Bind to a pit button; requires MatchContext.isDiagnostics(). */
  public static edu.wpi.first.wpilibj2.command.Command assignCommand(RobotId id);

  public static final class Builder {
    /** Match Platform.serialNumber() exactly. */
    public Builder bySerial(String serial, RobotId id);
    /** Case-insensitive substring of Platform.comments(). */
    public Builder byComment(String substring, RobotId id);
    /** Contents of Platform.persistentDir()/pumpkin/robot-id, trimmed, case-insensitive. On by default. */
    public Builder byPersistentFile(boolean enabled);
    /** Identity used when Platform.isSimulation(). Default SIM. */
    public Builder simIs(RobotId id);
    /** Used when nothing matched. Also raises an ERROR / BLOCKS_MATCH alert. */
    public Builder fallback(RobotId id);
    public void done();
  }
}
```

### 7.3 Per-robot constant overlays

Config objects are immutable records with `withX()` copy methods (domain 02's convention). Overlays are therefore just functions, and the compiler checks them — no JSON, no reflection, no string keys:

```java
// frc/robot/robots/RobotIds.java
public final class RobotIds {
  public static void register() {
    RobotIdentity.configure()
        .byPersistentFile(true)
        .byComment("practice", RobotId.PRACTICE)
        .bySerial("031b7f8e", RobotId.COMP)
        .simIs(RobotId.SIM)
        .fallback(RobotId.COMP)     // fail SAFE: a mystery robot behaves like the comp bot
        .done();
  }

  // Base config once; only the deltas are per-robot.
  public static final ElevatorConfig kElevator = RobotIdentity.overlay(
      ElevatorConfig.base()
          .withGearing(12.0)
          .withMaxHeight(Inches.of(58))
          .withStatorLimit(Amps.of(60)),
      Map.of(
          RobotId.PRACTICE, c -> c.withMaxHeight(Inches.of(54)).withStatorLimit(Amps.of(40)),
          RobotId.PROTO,    c -> c.withGearing(9.0)));
}
```

Every resolved config is registered with `ConfigRegistry` (§11.2) and logged as a struct into the WPILOG at boot, so "what config did this robot actually run?" is answerable from the log alone — the introspectability win of JSON config without giving up the compiler.

### 7.4 The build-time deploy gate

6328's real safety comes from build-time gates, not from runtime checks, and the gate is the half nobody copies. We ship it in the template — but with **two different severities, because the two checks have opposite failure economics.**

**The design error we are correcting.** A single hard gate on "working tree is dirty" bricks the robot at an event. The VS Code `WPILib: Deploy Robot Code` command and the F5 workflow — the only deploy interfaces most students have ever used — **cannot pass a `-P` property**. So at 8:40 on Saturday, a student who changes one soft limit to stop the arm hitting the chassis cannot deploy the fix at all from the interface they know, and the error message tells them to run git, in a pit, on venue wifi, while the mentor is at the field. That inverts `DESIGN.md` §3 principle 12, *degrade, never crash*: it converts a provenance nicety into a robot-is-dead failure. A library that stops a team from fixing their robot has done more damage than the untraceable commit ever would.

**The graded policy.**

| Check | Default | Rationale |
|---|---|---|
| `fallback(RobotId.SIM)` | **HARD FAIL, always, no override** | This build genuinely cannot produce a working robot. Deploying it wastes the match either way, so blocking costs nothing. |
| Dirty working tree | **WARN + deploy + stamp + persistent robot alert** | The fix reaches the robot; provenance is preserved by other means. |
| Dirty working tree, `-PstrictDeploy` | HARD FAIL | CI, and the team's own pre-event checklist. One click in VS Code (§6.2). |
| Dirty working tree, target has ever been FMS-attached, no `-PallowDirtyDeploy` | HARD FAIL | The robot itself says it has played a match. Now the burden of proof flips. |

```groovy
// applied by the template; task class lives in pumpkinlib's gradle plugin jar
tasks.register('pumpkinCheckDeploy') {
  doLast {
    def src = file('src/main/java/frc/robot/robots/RobotIds.java').text

    // ---- 1) HARD, unconditional. A SIM fallback cannot produce a working robot. ----
    if (src =~ /\.fallback\(\s*RobotId\.SIM\s*\)/) {
      throw new GradleException(
        "pumpkinCheckDeploy: RobotIds.fallback() is RobotId.SIM. A real robot would run simulation " +
        "config (wrong gearing, wrong limits, wrong offsets). Change it to COMP or PRACTICE in " +
        "src/main/java/frc/robot/robots/RobotIds.java. There is no override for this one.")
    }

    // ---- 2) GRADED. Dirty tree warns and deploys; it blocks only when you asked it to. ----
    def dirty = 'git status --porcelain'.execute().text.trim()
    if (!dirty) return

    // Has this robot ever been FMS-attached? Written by MatchContext into the persistent store
    // and pulled down by `pumpkin sync` into build/pumpkin/target-state.json. Absent == false,
    // because a missing file must never block a deploy.
    def stateFile = file("$buildDir/pumpkin/target-state.json")
    def everFms = stateFile.exists() && new groovy.json.JsonSlurper().parse(stateFile).everFmsAttached

    def strict  = project.hasProperty('strictDeploy')
    def allowed = project.hasProperty('allowDirtyDeploy')

    if (strict || (everFms && !allowed)) {
      throw new GradleException(
        "pumpkinCheckDeploy: working tree is dirty, so this build cannot be traced to a commit:\n" +
        dirty + "\n" +
        (strict ? "You asked for -PstrictDeploy.\n"
                : "This robot has been FMS-attached, so PumpkinLib assumes you are at an event.\n") +
        "Commit, or re-run with -PallowDirtyDeploy.\n" +
        "In VS Code: Tasks: Run Task -> 'Deploy (dirty, allowed)'.")
    }

    logger.warn("""
      ============================================================
      pumpkinCheckDeploy: DEPLOYING UNCOMMITTED CODE
      ${dirty}
      This build is not in git. BuildConstants.DIRTY = 1, the robot
      will raise a persistent warning, and /Pumpkin/Meta/GitDirty
      will be true. Commit when you are off the field.
      ============================================================""".stripIndent())
  }
}
```

Runtime half of the same policy (this is where the provenance actually gets preserved, and it costs the team nothing at 8:40 on Saturday):

* `BuildConstants.DIRTY == 1` → `/Pumpkin/Meta/GitDirty = true`, always, in the shop and at an event.
* A **persistent `WARNING`, `PIT_ONLY`** alert: *"This code is not in git (3 files changed). It cannot be reproduced from a commit."* It is `PIT_ONLY` by §8.2's triage rule — a dirty tree never stops a robot from playing a match, so it must never occupy one of the three driver-visible slots.
* `DeployInfo.summary()` prints `main@a1b2c3d (dirty)` on the pit display and in the log header, so the fact is on screen without being in the way.

`MatchContext` writes `{"everFmsAttached": true, "lastEvent": "CURIE", "at": "..."}` into `Platform.persistentDir()/pumpkin/target-state.json` on the FMS-attach rising edge; `pumpkin sync` (and the deploy task's pre-step, best-effort with a 500 ms timeout) copies it to `build/pumpkin/`. If the robot is unreachable, the file is stale or missing, and the deploy proceeds — **an unreachable robot must never be a reason to refuse to fix the robot.**

A companion `pumpkinCheckPullRequest` task (CI only) fails a PR unless tuning mode is off and the fallback is the comp bot. CI also always passes `-PstrictDeploy`, so the "never untraceable" property is enforced exactly where enforcement is free.

---

## 8. Alerts and Self-Test

### 8.1 Why this exists

WPILib ships `Alert` — the *display* primitive — and zero checks. Every check (CAN utilization, motor temp, sticky faults, brownout, encoder sanity, 5 V rail faults) must be written by the team. The two best public implementations are 3061-lib's `FaultReporter` and Team 135's Consul, and both are locked inside team repos: `FaultReporter`'s hardware coverage is a hardcoded overload list (adding a device means editing the class), and Consul requires `extends SubsystemChecker` — inheritance that is actively hostile to Commands v3.

We generalize both with **an open SPI plus adapters**, so a new vendor is a new artifact, never an edit to core.

### 8.2 Alerts — severity is not the same question as "can we play?"

**The failure mode we are designing against is not a missing alert. It is fifteen simultaneous expected ones.**

Count the alert sites on a realistic robot: ten built-in monitors here (§8.5), eleven automatic per-mechanism alerts from design/01 §1.3, nineteen `RejectReason` values with per-camera counters from design/03, plus design/01 §5.6's tier-2 config warnings. A four-mechanism robot with swerve and two cameras has **60+ alert sites**. On a half-built week-2 robot a completely normal simultaneous set is: 4× motor-disconnected, 2× absolute-encoder-disconnected, 3× kG-is-zero, 4× homing-not-done, git-dirty, CAN-utilization, 2× camera-not-seen. That is roughly **fifteen red rows in Elastic's Alerts widget, every one of them expected.**

Students learn within a week that the alert panel is noise. At that moment principle 3 — zero-mystery debugging — is dead, and the one real alert on Saturday morning is indistinguishable from the fifteen they have been scrolling past since January. Severity does not fix this: "the elevator encoder is unplugged" and "you haven't tuned kG yet" are both honestly `ERROR`, and only one of them means don't take the field.

So every alert declares a **second, orthogonal axis**: does this mean the robot should not take the field?

```java
package org.pumpkinlib.core.alert;

import edu.wpi.first.wpilibj.Alert.AlertType;

/** Ordered severity. Maps 1:1 to WPILib AlertType; exists so we can do rollup and comparison. */
public enum Severity {
  INFO, WARNING, ERROR;
  public AlertType toWpi();
  public static Severity from(AlertType t);
}
```

```java
package org.pumpkinlib.core.alert;

/**
 * Orthogonal to Severity, and REQUIRED at every call site. There is no default and no
 * single-argument overload: the author of an alert is the only person who knows the answer,
 * and forcing them to type it is the entire mechanism.
 *
 * Deliberately an enum and not the `boolean blocksMatch` a reviewer asked for. `alert(..., true)`
 * at a call site is unreadable and gets copy-pasted wrong; `BLOCKS_MATCH` cannot be. Same
 * requirement, same enforcement, better call sites — this library is read by students.
 */
public enum MatchImpact {
  /** The robot should not take the field like this. Eligible for the driver mirror. */
  BLOCKS_MATCH,
  /** True, worth fixing, not match-stopping. Pit tab only. Never reaches the driver. */
  PIT_ONLY
}
```

**Reviewer pushback:** the review asked for a required `boolean blocksMatch` parameter on
`Alerts.error/warning/info(...)`.
**Why we're doing it anyway:** we adopt the requirement in full — required at every call site, no
default, no single-argument overload, and the same five downstream consequences (driver mirror,
`Ready` rollup, CI budget gate, `.expectAbsent()`) — but as an enum rather than a `boolean`. A
`boolean` produces call sites that read `PumpkinAlert.error("Arm", "leader disconnected", true)`,
which is unreadable at the point it matters most and gets copy-pasted with the wrong literal by the
exact audience this library is for. `MatchImpact.BLOCKS_MATCH` cannot be copy-pasted wrong and
cannot be silently inverted. The enforcement mechanism — you cannot compile without answering the
question — is identical; only the legibility differs, and this library is read by students.

```java
package org.pumpkinlib.core.alert;

import java.util.List;
import java.util.function.BooleanSupplier;
import edu.wpi.first.units.measure.Time;

/**
 * A registered alert. Wraps a WPILib Alert (so stock dashboard widgets work) and adds
 * registry membership, cycle-limited text updates, rising-edge notification, and latching.
 */
public final class PumpkinAlert implements AutoCloseable {

  public static PumpkinAlert of(String group, String text, Severity severity, MatchImpact impact);

  /** Self-driving: evaluated by the alert registry on its round-robin slice. Preferred form. */
  public static PumpkinAlert when(
      String group, String text, Severity s, MatchImpact impact, BooleanSupplier cond);

  /** Sugar. Still requires the impact argument — that is the point. */
  public static PumpkinAlert error(String group, String text, MatchImpact impact);
  public static PumpkinAlert warning(String group, String text, MatchImpact impact);
  public static PumpkinAlert info(String group, String text);   // INFO is PIT_ONLY by definition

  public PumpkinAlert set(boolean active);
  public boolean isActive();
  public MatchImpact impact();

  /**
   * Demote to PIT_ONLY and INFO because this hardware is knowingly not installed.
   * Set by Mechanism config's .expectAbsent() (§8.5) — not by hand at the alert site.
   */
  PumpkinAlert demoteExpectedAbsent(String reason);

  /** Live text, e.g. "Arm leader at 84 C (limit 70 C)". Coalesced to one update per 25 cycles. */
  public PumpkinAlert text(String text);

  /** Once true, stays true until clearLatched(). For transient events like a brownout. */
  public PumpkinAlert latching();
  public PumpkinAlert clearLatched();

  /**
   * Suppress until the condition has held this long. Kills flapping alerts.
   * Converted ONCE to a whole number of loops via Clock.cyclesFor(duration) and counted in
   * cycles thereafter, so a replayed log debounces on exactly the cycles the real robot did.
   */
  public PumpkinAlert debounce(Time duration);

  /** Send an Elastic notification on the rising edge only (never in periodic). Default true for ERROR. */
  public PumpkinAlert notifyDriver(boolean enabled);

  /** Escape hatch. */
  public edu.wpi.first.wpilibj.Alert raw();

  @Override public void close();
}
```

```java
package org.pumpkinlib.core.alert;

import java.util.List;
import java.util.Optional;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/** Answers the one question nobody can currently answer: is the robot OK right now? */
public final class AlertRegistry {
  public static void register(PumpkinAlert a);
  public static List<PumpkinAlert> active();
  public static Optional<Severity> worst();

  /** Active alerts with impact == BLOCKS_MATCH, ranked: severity desc, then registration order. */
  public static List<PumpkinAlert> blocking();

  /**
   * NO active BLOCKS_MATCH alert. This is "this robot can play a match", NOT "nothing anywhere
   * is imperfect". Drives LEDs, the pit display, and the self-test gate.
   */
  public static boolean matchReady();

  public static Trigger anyError();
  public static Trigger anyWarning();
  public static Trigger anyBlocking();

  /** Bridge every rising-edge activation to an Elastic notification. Called by PumpkinRobot. */
  public static void bridgeToDashboard();
}
```

#### 8.2.1 The driver mirror — three rows, hard cap

`/Pumpkin/Driver/` (the D22 driver mirror) shows **only `BLOCKS_MATCH` alerts, capped at three**, ranked by severity then registration order, plus a rollup row when there are more:

```
/Pumpkin/Driver/Blocking      string[]   at most 3 entries
/Pumpkin/Driver/BlockingMore  string     "+4 more - see the pit tab"  (empty when none)
/Pumpkin/Driver/Ready         boolean    == AlertRegistry.matchReady()
```

Everything else — every `PIT_ONLY` alert, every INFO, every per-camera reject counter — lives on the pit tab only, at `/Pumpkin/Health/<Source>/`. The shipped `elastic-layout.json` binds the driver tab's Alerts widget to `/Pumpkin/Driver/` and the pit tab's to `/Pumpkin/Health/`. Three rows is a number a driver can read in the two seconds between "robot's on the field" and "hands on the sticks"; fifteen is a wall of red that trains people to ignore it.

#### 8.2.2 `AlertBudgetTest` — a CI gate on the noise floor

Budgets that are not enforced are aspirations. `pumpkin-testkit` ships the test and the template runs it:

```java
package org.pumpkinlib.testkit;

/**
 * Boots the §13 example robot headless in sim with ALL hardware absent — the worst honest
 * case, and exactly what a week-2 robot looks like — and asserts the driver is not buried.
 */
class AlertBudgetTest {

  @Test
  void bareRobotDoesNotFloodTheDriver() {
    var robot = HeadlessRobot.boot(RobotContainer::new);
    robot.tick(250);                                    // 5 s at 50 Hz; debounces have expired

    var blocking = AlertRegistry.blocking();
    assertThat(blocking)
        .withFailMessage(() ->
            "Alert budget exceeded: " + blocking.size() + " BLOCKS_MATCH alerts with no hardware "
          + "present. Each one of these claims the robot cannot play a match:\n  "
          + blocking.stream().map(PumpkinAlert::toString).collect(joining("\n  "))
          + "\nIf one of these is really a 'a programmer should look at this' alert, mark it "
          + "MatchImpact.PIT_ONLY. If the hardware is knowingly not installed, use "
          + ".expectAbsent() in the mechanism config.")
        .hasSizeLessThanOrEqualTo(5);
  }
}
```

Five, not zero: a robot with no hardware genuinely cannot play a match, and "drive: no motors responded" *should* block. Five is the number of distinct root causes a student can hold in their head. When a new built-in alert pushes it to six, the failure message tells the library author which axis they got wrong — which is the same zero-mystery contract we make to users, applied to ourselves.

`AlertRegistry` publishes `/Pumpkin/Health/Summary/{Worst, ActiveCount, BlockingCount, MatchReady}` and the per-group detail. It is evaluated on the round-robin health slice (§8.3), never at 50 Hz and never on a wall clock.

### 8.3 Health monitoring — slice it, and count cycles not milliseconds

DESIGN.md §3 principle 10 cites Team 135's measurement of **~10 ms/loop for full fault checking** and answered it with "4 Hz (not 50 Hz) health polling." That answer is wrong twice, and both errors are load-bearing.

**Error 1 — it converts a sustained tax into a spike.** Polling everything at 4 Hz does not make the work cheaper; it makes it *bursty*. On a 20 ms budget already carrying ~8 ms of PumpkinLib overhead plus user code, a 10 ms burst every 250 ms means **every twelfth loop overruns**. Loop overruns that appear once every 250 ms are the single worst thing to debug, because they correlate with nothing a student can see. And `LoopTimeMonitor` (§8.5) would faithfully report a library-caused overrun — from the health monitor whose job is to prevent mystery.

**Error 2 — "4 Hz" against a wall clock is nondeterministic under replay.** The same log replayed at 50× fires the monitors on different cycles than the real robot did, so replayed health outputs do not match recorded ones and `PumpkinReplayVerify` reports diffs that are artifacts of the harness. A replay tool that cries wolf is a replay tool nobody runs.

**The fix: `HealthMonitor` runs exactly ONE registered source per robot loop, round-robin.**

```java
// the entire scheduling policy
m_sources.get((int) (m_cycle++ % m_sources.size())).pollHealth(m_collector);
```

Consequences, all of them good:

* **Bounded per-loop cost.** One source per loop, ~1 ms worst case instead of a 10 ms spike. The tail is flat, which is what a 20 ms budget actually requires.
* **A full sweep every `slices.size()` cycles.** The default registration is eleven slices — eight built-in health sources (`CanBusMonitor.rio()`, `BatteryMonitor`, `RailMonitor.watch5V()`, `watch3V3()`, `watch6V()`, `BrownoutMonitor`, `DeployMonitor`, `DsMonitor`) plus three platform slices (`AlertRegistry` evaluation, `MatchContext` derived publishing, `TuningRegistry.periodic()`). Eleven slices at 50 Hz is a complete sweep every **220 ms** — within a rounding error of the 250 ms the 4 Hz gate gave, at roughly a twelfth of the peak cost. `LoopTimeMonitor` is *not* a slice: it is measuring loops, so it runs every loop, and its ~30 µs is in its own budget.
* **A four-mechanism robot is still fine.** Add a swerve drive, two cameras and four mechanisms and you are at ~18 slices — a 360 ms sweep. That is the correct thing to degrade: a check that fires a third of a second later is still a check, whereas a loop overrun corrupts every control loop on the robot.
* **Cycle-deterministic.** Source *k* is polled on cycles ≡ *k* (mod *n*), in real time, in replay, in sim, and in a JUnit test. Replay diffs mean something again.
* **Self-limiting by construction.** Registering an eleventh monitor lengthens the sweep; it does not raise the per-loop cost. The cost of "one more check" is latency, not overruns — the right thing to trade.

**This rule is domain-wide.** Every wall-clock rate gate in this domain is deleted; rates are expressed as `everyNCycles(int)` derived from `Clock.periodCycles(Hertz.of(4))`. That includes `MatchContext.poll()` and domain 08's `TuningRegistry.periodic()`, both of which are on the same round-robin (§8.3.1). Anything that must run every loop — `LedController`, `RumbleScheduler`, the `PumpkinTracer` accumulators — says so explicitly and is budgeted.

```java
package org.pumpkinlib.core.diag;

import edu.wpi.first.units.measure.Time;

/**
 * Per-section loop-time accounting with declared budgets. LoopTimeMonitor (§8.5) turns a
 * budget overrun into a named alert; this is where the budget is declared and the time measured.
 * Cost is one Clock.seconds() pair per section per loop.
 */
public final class PumpkinTracer {
  /** Declare a budget. Exceeding it for 5 consecutive sweeps raises a PIT_ONLY WARNING alert. */
  public static void budget(String section, Time perLoop);

  /** try-with-resources timing block. Publishes /Pumpkin/Loop/Domain/<section>Ms. */
  public static AutoCloseable section(String section);

  public static Time p95(String section);
  public static Time worst(String section);
}
```

Budgets PumpkinLib declares for itself at boot, so "is PumpkinLib causing my overruns?" is answerable without bisecting:

```java
PumpkinTracer.budget("Health",    Milliseconds.of(1.5));   // one source per loop, round-robin
PumpkinTracer.budget("Alerts",    Milliseconds.of(0.5));
PumpkinTracer.budget("Match",     Milliseconds.of(0.2));
PumpkinTracer.budget("Power",     Milliseconds.of(0.5));
PumpkinTracer.budget("Leds",      Milliseconds.of(1.0));   // every loop by design
PumpkinTracer.budget("Telemetry", Milliseconds.of(2.0));
```

#### 8.3.1 The round-robin, precisely

One shared rotation, not one per subsystem, because *n* independent rotations reconstruct the spike:

```java
package org.pumpkinlib.core.health;

/**
 * The single slice scheduler. PumpkinRobot calls tick() exactly once per robotPeriodic().
 * Everything periodic-but-not-every-loop in PumpkinLib registers here; nothing else may
 * implement its own rate gate.
 */
public final class SliceScheduler {
  /** Registered in construction order; order is part of the replay contract, so it is stable. */
  public static void register(String name, Runnable slice);

  /** Runs exactly ONE registered slice: slices.get(cycle % slices.size()). */
  static void tick();

  /** Cycles between two runs of the same slice == number of registered slices. */
  public static int sweepCycles();
}
```

`MatchContext.poll()`, `TuningRegistry.periodic()`, `AlertRegistry` evaluation, and every `HealthSource` are slices. Three things are explicitly **not** slices and run every loop, because their whole value is edge detection: `MatchContext`'s FMS/DS/alliance edge latching (~20 µs of boolean reads — the *derived* publishing is the slice), `RumbleScheduler`, and `LedController`.

```java
package org.pumpkinlib.core.health;

import edu.wpi.first.units.measure.Time;
import org.pumpkinlib.core.alert.Severity;

/** One observed problem. Immutable; produced fresh each poll. */
public record Fault(
    String device,        // "Arm/leader (TalonFX 21)"
    String description,   // "device temperature 84.2 C exceeds warn limit 70.0 C"
    Severity severity,
    boolean sticky) {}    // sticky = latched by the device across boots
```

```java
package org.pumpkinlib.core.health;

/** Passed into pollHealth(); collects without allocating a list per poll. */
public interface FaultCollector {
  void add(Fault f);
  default void error(String device, String description)   { add(new Fault(device, description, Severity.ERROR, false)); }
  default void warn(String device, String description)    { add(new Fault(device, description, Severity.WARNING, false)); }
  default void sticky(String device, String description)  { add(new Fault(device, description, Severity.WARNING, true)); }
}
```

```java
package org.pumpkinlib.core.health;

public final class HealthMonitor {

  /** Registers a source as one slice of the shared round-robin. Idempotent by healthName(). */
  public static HealthMonitor watch(HealthSource source);

  /**
   * Poll this source only every Nth time its slot comes up, for genuinely expensive checks
   * (a device config read-back, say). Default 1 == every sweep. There is deliberately NO
   * rate(Frequency) method: a Hz value implies a wall clock, and wall clocks are what §8.3
   * removed. Convert with Clock.periodCycles(Hertz.of(...)) if you are thinking in Hz.
   */
  public HealthMonitor everyNSweeps(int n);

  /** Only poll when MatchContext.isDiagnostics(). Off by default: cheap checks must run in a match. */
  public HealthMonitor onlyInDiagnostics(boolean b);

  /**
   * This device is knowingly not installed on this robot right now (mid-build, swapped out,
   * next year's mechanism). Its disconnect/absence faults are demoted to INFO + PIT_ONLY and
   * printed in the boot dump instead of the alert panel. Set from mechanism config's
   * .expectAbsent() (domain 02), never guessed by the library.
   */
  public static void expectAbsent(String healthName, String reason);

  /** Poll every source once, right now, ignoring the round-robin. Used ONLY by SelfTest. */
  public static void pollAll();

  /** How many loops a full sweep takes right now. Published to /Pumpkin/Health/SweepCycles. */
  public static int sweepCycles();

  /** Snapshot of every fault seen since enable, for expectNoNewFaults(). */
  public static java.util.Set<String> faultFingerprints();
}
```

`pollAll()` is the one place the 10 ms burst is acceptable, because `SelfTest` runs disabled in the pit with no control loop to starve. It logs its own duration to `/Pumpkin/SelfTest/PollAllMs` so the cost stays visible rather than becoming folklore.

**Boot dump for expected-absent hardware.** `.expectAbsent()` does not silence anything — it *relocates* it. At boot, `PumpkinRobot` prints one block to the console and `/Pumpkin/Health/ExpectedAbsent`:

```
PumpkinLib: 3 devices declared absent (alerts demoted to INFO)
  Climber/leader  (TalonFX 31)  - "not built yet, week 2"
  Climber/encoder (CANcoder 32) - "not built yet, week 2"
  Intake/beambreak (DIO 4)      - "sensor on order"
Remove .expectAbsent() from ClimberConfig when the hardware lands.
```

That is the difference between suppression and triage: the fact is still on screen, still named, still attributable — it just is not claiming the robot cannot play a match.

```java
package org.pumpkinlib.core.health;

import java.util.List;
import java.util.Optional;
import org.pumpkinlib.core.alert.Severity;

public final class RobotHealth {
  public static Optional<Severity> worst();
  public static List<Fault> active();

  /**
   * THE match-readiness answer, and it rolls up BLOCKS_MATCH alerts EXCLUSIVELY (§8.2).
   * "Ready" means "this robot can play a match", not "nothing anywhere is imperfect".
   * A robot with nine PIT_ONLY warnings and no blocking alerts is Ready, and the LEDs go green,
   * because that is the honest answer and an honest answer is what makes the signal trusted.
   */
  public static boolean isReady();          // delegates to AlertRegistry.matchReady()

  /** Deprecated spelling of isReady(), kept for one season. AlertRegistry.matchReady() is the
   *  canonical implementation and is NOT deprecated — this is the duplicate, not that one. */
  @Deprecated public static boolean matchReady();

  public static boolean hasError();
  public static boolean hasWarning();

  /** True while battery voltage is sagging past the configured limit. Used by CompressorPolicy. */
  public static boolean batterySagging();
}
```

Vendor adapters (each in its own artifact, each a `HealthSource` factory — **no overload list in core**):

```java
package org.pumpkinlib.phoenix6;

import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.hardware.Pigeon2;
import edu.wpi.first.units.measure.Temperature;
import org.pumpkinlib.core.health.HealthSource;

public final class Phoenix6Health {
  public static Builder of(String label, TalonFX motor);
  public static HealthSource of(String label, CANcoder encoder);
  public static HealthSource of(String label, Pigeon2 gyro);

  public static final class Builder {
    /** Checks isConnected(), every getStickyFault_* signal, and getDeviceTemp(). */
    public Builder tempLimits(Temperature warn, Temperature error);
    public Builder clearStickyFaultsOnBoot(boolean b);   // default false: previous-match faults MUST surface
    public HealthSource build();
  }
}
```

`org.pumpkinlib.revlib.RevHealth` mirrors it over `SparkBase.getFaults()`, `getStickyFaults()`, `getWarnings()`, `getStickyWarnings()`, `hasActiveFault()`, `getMotorTemperature()`.

**Sticky faults from a previous boot are surfaced immediately at startup** and never auto-cleared, because that is how you catch a fault that happened in the previous match.

### 8.4 Self-test

135's Consul + Squire proves the workflow — run in Test mode, sequential, per-step pass/fail on a pit screen — but requires inheritance and a separate desktop app. We keep the semantics and drop both.

```java
package org.pumpkinlib.core.selftest;

import java.util.function.DoubleSupplier;
import java.util.function.Supplier;
import edu.wpi.first.units.Unit;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.units.measure.Current;
import edu.wpi.first.wpilibj2.command.Command;

/** A named, ordered sequence of steps with expectations. Built with the DSL below. */
public final class SelfTestRoutine {
  public static Builder of(String mechanismName);

  public static final class Builder {
    public StepBuilder step(String name, Command action);
    public Builder expectCurrentBetween(Current min, Current max);
    public Builder expectNoNewFaults();
    /** Steps are aborted and marked FAIL if any registered ERROR alert activates mid-run. */
    public Builder abortOnError(boolean b);
    public SelfTestRoutine build();
  }

  public static final class StepBuilder {
    /** Assert a measured quantity settles within tolerance of a target before the timeout. */
    public <U extends Unit> StepBuilder expect(
        Supplier<edu.wpi.first.units.Measure<U>> measured,
        edu.wpi.first.units.Measure<U> target,
        edu.wpi.first.units.Measure<U> tolerance);
    public StepBuilder expect(DoubleSupplier measured, double target, double tolerance, String unitLabel);
    /** Assert something changed at all — catches a disconnected encoder that reads a constant. */
    public StepBuilder expectMoved(DoubleSupplier measured, double minDelta, String unitLabel);
    public StepBuilder withTimeout(Time t);
    public StepBuilder step(String name, Command action);   // chain to the next step
    public SelfTestRoutine build();
  }
}
```

```java
package org.pumpkinlib.core.selftest;

import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

public final class SelfTest {
  /** Registration is by interface, so a mechanism opts in with no base class. */
  public static void register(SelfTestable target);
  public static void register(String name, java.util.function.Supplier<SelfTestRoutine> routine);

  /** Sequences every registration; catches, times, and publishes each. Bind to a pit button. */
  public static Command runAll();
  public static Command run(String name);

  public static List<SelfTestResult> lastResults();

  /** Human-readable block for the Elastic text widget and the pit printout. */
  public static String summary();
}
```

```java
package org.pumpkinlib.core.selftest;

import java.util.List;

public record SelfTestResult(
    String name, boolean passed, double durationSec, List<StepResult> steps, String detail) {

  public record StepResult(String name, boolean passed, String expectation, String observed) {}
}
```

Publishes `/Pumpkin/SelfTest/<name>/{Status, Passed, Ran, Detail, DurationSec}` and `/Pumpkin/SelfTest/Summary`. Safety: `runAll()` refuses to schedule unless `MatchContext.isDiagnostics()` is true, or `MatchContext.isDisabled() && !MatchContext.isFMSAttached()`, and it raises an `ERROR` / `BLOCKS_MATCH` alert saying exactly why it refused. `abortOnError(true)` aborts on any *blocking* alert activating mid-run, not on any alert at all — otherwise a `PIT_ONLY` kG warning aborts the self-test that would have found the real problem.

The pit workflow is: **plug in, enable Test, press one button, read a green/red list on Elastic.** Ninety seconds, repeatable, no tribal knowledge.

Example self-test written by a team (this is the whole thing):

```java
SelfTest.register("Arm", () -> SelfTestRoutine.of("Arm")
    .step("extend", arm.toAngle(Degrees.of(90)))
        .expect(arm::angle, Degrees.of(90), Degrees.of(2)).withTimeout(Seconds.of(2))
    .step("retract", arm.toAngle(Degrees.of(0)))
        .expect(arm::angle, Degrees.of(0), Degrees.of(2)).withTimeout(Seconds.of(2))
    .expectCurrentBetween(Amps.of(2), Amps.of(35))
    .expectNoNewFaults()
    .build());
```

### 8.5 Built-in monitors — on by default

The vendor adapters are the easy half. These are the checks nobody writes, every one of them backed by a documented WPILib API that most teams have never heard of. **`PumpkinRobot` registers all of them automatically**; each can be reconfigured or disabled by name.

```java
package org.pumpkinlib.core.health.builtin;

import edu.wpi.first.units.measure.*;

/**
 * CAN bus utilization + error-counter DELTAS since enable. Absolute counters are meaningless.
 * Multi-bus aware from day one: SystemCore has several buses.
 */
public final class CanBusMonitor {
  /** roboRIO bus, via Platform.canStatus() (which wraps RobotController.getCANStatus()). */
  public static CanBusMonitor rio();
  /**
   * Any bus via a supplier, so the Phoenix adapter can feed CANivore/SystemCore buses using
   * com.ctre.phoenix6.CANBus.getStatus() -> CANBusStatus{Status, BusUtilization, BusOffCount,
   * TxFullCount, REC, TEC}.  (verified against Phoenix 6 26.1.3 Javadoc, 2026-08-06)
   */
  public static CanBusMonitor named(String busName, java.util.function.Supplier<CanSample> sampler);

  public CanBusMonitor warnAbove(double utilizationFraction);   // default 0.70
  public CanBusMonitor errorAbove(double utilizationFraction);  // default 0.90
  public CanBusMonitor watchErrorDeltas(boolean b);             // default true

  public record CanSample(double utilization, int busOff, int txFull, int rec, int tec) {}
}

/** Resting voltage, sag under load, and joules consumed. Correlates sag against PDH total current. */
public final class BatteryMonitor {
  public static BatteryMonitor create();
  public BatteryMonitor restingMin(Voltage v);   // default 12.3 V — below this, rotate the battery out
  public BatteryMonitor sagLimit(Voltage v);     // default 9.0 V
  public BatteryMonitor sagWindow(Time t);       // default 2 s
  public Voltage minSeenSinceEnable();
  public Voltage restingEstimate();
}

/** Platform.faultCount5V() != 0 means sensors browned out and your encoders lied. */
public final class RailMonitor {
  public static RailMonitor watch5V();
  public static RailMonitor watch3V3();
  public static RailMonitor watch6V();
}

/** Platform.isBrownedOut(), latched with a Clock.seconds() stamp so a 40 ms brownout is not missed. */
public final class BrownoutMonitor { public static BrownoutMonitor latching(); }

/** BuildConstants.DIRTY == 1 -> WARNING / PIT_ONLY "deployed code is not in git" (§7.4, §11.1). */
public final class DeployMonitor { public static DeployMonitor create(); }

/**
 * Per-domain loop timing against the budgets declared with PumpkinTracer (§8.3).
 * Answers "is PumpkinLib causing my overruns?" in one glance, with a name attached.
 * Runs EVERY loop (it is measuring loops); its own cost is ~30 us and it is in its own budget.
 */
public final class LoopTimeMonitor {
  public static LoopTimeMonitor create();
  public LoopTimeMonitor budget(Time period);                // default: Clock.period()
  public LoopTimeMonitor warnAbove(double fractionOfBudget); // default 0.80
  /** Delegates to PumpkinTracer.section(name); kept here because this is where people look. */
  public static AutoCloseable section(String name);
}

/** Joystick presence and slot verification against the declared ControlMap. */
public final class DsMonitor { public static DsMonitor create(); }
```

**Every built-in monitor declares its `MatchImpact`, and most of them are `PIT_ONLY`.** This table is the difference between a driver panel that gets read and one that gets ignored:

| Monitor | Condition | Severity | Impact |
|---|---|---|---|
| `CanBusMonitor` | utilization > 90%, or bus-off delta > 0 | ERROR | **BLOCKS_MATCH** |
| `CanBusMonitor` | utilization > 70% | WARNING | PIT_ONLY |
| `BatteryMonitor` | resting < 12.3 V at enable | WARNING | **BLOCKS_MATCH** — this is the classic lost match, and it is fixable in 90 seconds |
| `BatteryMonitor` | sag < 9.0 V sustained | WARNING | PIT_ONLY |
| `RailMonitor` | 5 V fault count rising | ERROR | **BLOCKS_MATCH** — your encoders are lying right now |
| `RailMonitor` | 3.3 V / 6 V fault count rising | WARNING | PIT_ONLY |
| `BrownoutMonitor` | latched brownout since enable | ERROR | PIT_ONLY — it already happened; the driver cannot act on it |
| `DeployMonitor` | `DIRTY == 1` | WARNING | PIT_ONLY |
| `DeployMonitor` | provenance UNKNOWN | INFO | PIT_ONLY |
| `LoopTimeMonitor` | over budget 5 sweeps running | WARNING | PIT_ONLY |
| `DsMonitor` | declared HID port empty or wrong controller | ERROR | **BLOCKS_MATCH** — half the bindings are dead |

Four of eleven built-in conditions can reach the driver, and each is something a human can act on in the ninety seconds before a match. That is the budget `AlertBudgetTest` (§8.2.2) defends.

`LoopTimeMonitor` is a direct response to the strongest recurring criticism of every FRC framework: *"For us, YAMS caused more problems than it solved. The structure of it created a crazy amount of loop overruns… We ended up not using any logging or telemetry at all."* A library whose observability is the first thing dropped under pressure has failed at its main job. We publish `/Pumpkin/Loop/Domain/<name>Ms` for every PumpkinLib domain on by default, so blame is attributable rather than bisected.

Encoder sanity and stall detection belong to the mechanism domain (they need a mechanism's two sensors), but they emit through this registry:

```java
package org.pumpkinlib.core.health;

import edu.wpi.first.units.measure.*;
import java.util.function.DoubleSupplier;

public final class Checks {
  /** Two sensors that must agree. The canonical "CANcoder is on the wrong shaft" detector. */
  public static HealthSource encoderAgreement(
      String label, DoubleSupplier a, DoubleSupplier b, Angle maxDisagreement);

  /** High current + near-zero velocity for a sustained time = something is jammed. */
  public static HealthSource stall(
      String label, DoubleSupplier amps, DoubleSupplier velocity,
      Current currentAbove, double velocityBelow, Time forTime);

  /** Commanded to move but the measurement did not change. Catches an unplugged encoder. */
  public static HealthSource notMoving(
      String label, DoubleSupplier command, DoubleSupplier measured, Time forTime);
}
```

---

## 9. Power, Pneumatics, LEDs, Haptics, Dashboard

### 9.1 Power monitoring and the current-limit budget

`PowerDistribution.getAllCurrents()` returns a `double[]` indexed by bare channel number, so PDH logs are unreadable and nobody notices channel 7 sitting at 45 A on a 40 A breaker until it trips in elims. `getTotalEnergy()` is a perfect per-match battery metric that is essentially never used.

```java
package org.pumpkinlib.core.power;

import edu.wpi.first.wpilibj.PowerDistribution;
import edu.wpi.first.wpilibj.PowerDistribution.ModuleType;
import edu.wpi.first.units.measure.*;
import edu.wpi.first.wpilibj2.command.Command;

public final class PowerMonitor {
  public static PowerMonitor of(PowerDistribution pdh);
  public static PowerMonitor pdh();                 // new PowerDistribution(1, ModuleType.kRev)
  public static PowerMonitor pdp();                 // new PowerDistribution(0, ModuleType.kCTRE)

  /** Names a channel and declares its breaker. Auto-alerts at 90% of the breaker, sustained 1 s. */
  public PowerMonitor channel(int ch, String name, int breakerAmps);
  public PowerMonitor warnAbove(int ch, Current i, Time sustained);

  public Current total();
  public Voltage voltage();
  /** getTotalEnergy() since enable — the metric that makes a battery rotation discipline possible. */
  public Energy matchEnergy();

  /** PDH only. */
  public Command switchableChannel(boolean on);

  /** Escape hatch. */
  public PowerDistribution raw();
}
```

```java
package org.pumpkinlib.core.power;

import edu.wpi.first.units.measure.Current;

/**
 * Robot-level sum of every mechanism's DECLARED current limit. Nothing in the vendor APIs
 * checks that the sum of six 120 A stator limits exceeds what the battery can deliver.
 */
public final class PowerBudget {
  /** Called automatically by every PumpkinLib mechanism at construction. */
  public static void declare(String mechanism, Current stator, Current supply);
  public static Current totalDeclaredStator();
  public static Current totalDeclaredSupply();
  /** Logs the whole table and raises WARNING / PIT_ONLY above the threshold. Default 400 A supply. */
  public static void publish(Current supplyWarnThreshold);
}
```

Current limits are a **required** argument of mechanism construction (domain 02's contract with us), not an optional one. That is deliberate teaching: CTRE's own guidance is that stator limits cap torque and are the more effective brownout guard at the start of acceleration, while supply limits protect the battery and breakers during sustained load. Making the limit a named, unit-typed, required argument teaches the distinction instead of hiding it. We also print CTRE's ordering advice in the `PowerBudget` boot log: **check battery health and PDH/PDP crimps before clamping limits in software** — a high-resistance connection produces brownouts no software limit can fix.

### 9.2 Pneumatics — first-class, but not shoehorned into the motor API

**Direct answer to the question: no, pneumatic actuators must not be abstracted alongside motors.** A `DoubleSolenoid` has no position, no velocity, no current, no feedback, and no continuum of setpoints. Forcing it into a `Mechanism` interface with `setPosition`/`getVelocity`/`currentLimit` produces an abstraction where 80% of the methods throw or return `NaN`, which is strictly worse than no abstraction. Model it as what it is: a discrete-state actuator.

```java
package org.pumpkinlib.core.pneumatics;

import edu.wpi.first.wpilibj.DoubleSolenoid;
import edu.wpi.first.wpilibj.PneumaticsModuleType;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/** A two-state actuator. Deliberately NOT a Mechanism. */
public final class Pneumatic {
  public static Pneumatic doubleActing(String name, PneumaticsModuleType type, int fwd, int rev);
  public static Pneumatic singleActing(String name, PneumaticsModuleType type, int channel);

  public Command extend();
  public Command retract();
  public Command toggle();

  /** Commanded state only — no feedback exists on a solenoid, and we say so. */
  public Trigger isExtended();

  /** Free short-circuit detection via isFwdSolenoidDisabled()/isRevSolenoidDisabled(). */
  public org.pumpkinlib.core.health.HealthSource health();

  public DoubleSolenoid raw();
}
```

```java
package org.pumpkinlib.core.pneumatics;

import edu.wpi.first.wpilibj.Compressor;
import edu.wpi.first.units.measure.Pressure;
import java.util.function.BooleanSupplier;

public final class CompressorPolicy {
  public static CompressorPolicy of(Compressor c);

  public CompressorPolicy digital();                                  // enableDigital()
  public CompressorPolicy analog(Pressure min, Pressure max);         // enableAnalog(min, max)
  public CompressorPolicy hybrid(Pressure min, Pressure max);         // enableHybrid(min, max)

  /** The compressor is a large uncommanded draw that nobody models in a brownout budget. */
  public CompressorPolicy disableWhen(BooleanSupplier condition);

  /** Sensible default policy, applied by PumpkinRobot if the user does nothing else. */
  public CompressorPolicy standard();  // disable during auto and while RobotHealth.batterySagging()

  public Compressor raw();
}
```

`PneumaticsHealth.of(hub, compressor)` emits solenoid-short alerts, low-pressure alerts, and compressor over-current.

### 9.3 LEDs — one state machine, two backends

WPILib's `LEDPattern` algebra is genuinely excellent and almost nobody discovers `mask()`, `overlayOn()`, or `progressMaskLayer()`. Meanwhile CTRE moved CANdle into Phoenix 6 for 2026 with a completely incompatible control-request model, so a team that changes LED hardware rewrites all their LED code. And neither provides **arbitration** — the universal failure is a 200-line if/else ladder in `periodic()` where two subsystems fight over the strip. The arbitration layer does not exist anywhere; we build it.

```java
package org.pumpkinlib.core.led;

import edu.wpi.first.wpilibj.LEDPattern;
import edu.wpi.first.wpilibj.LEDReader;
import java.util.function.BooleanSupplier;

/** Writes a resolved buffer to hardware. Implementations: AddressableBackend, CandleBackend. */
public interface LedBackend extends AutoCloseable {
  int length();
  void write(LEDReader buffer);
  @Override void close();
}
```

```java
package org.pumpkinlib.core.led;

import edu.wpi.first.wpilibj.AddressableLED;
import edu.wpi.first.wpilibj.AddressableLEDBuffer;
import edu.wpi.first.wpilibj.LEDPattern;
import edu.wpi.first.wpilibj2.command.Command;
import java.util.function.BooleanSupplier;

/**
 * Declarative, priority-arbitrated robot-state signaling. Resolves the highest-priority
 * active state each cycle and writes ONCE. No if/else ladder anywhere in team code.
 */
public final class LedController {
  public static LedController on(LedBackend backend);

  /** Higher priority wins. Ties broken by registration order. */
  public LedController state(int priority, BooleanSupplier when, LEDPattern show);

  /** Logical segment of one physical strip, via AddressableLEDBuffer.createView(start, end). */
  public LedRegion region(String name, int start, int end, boolean reversed);

  /** Shown when nothing else is active. */
  public LedController idle(LEDPattern p);

  /** Register with the scheduler. PumpkinRobot does this for you. */
  public void start();

  public AddressableLEDBuffer rawBuffer();

  public static final class LedRegion {
    public LedRegion state(int priority, BooleanSupplier when, LEDPattern show);
    public LedRegion idle(LEDPattern p);
    public LedController done();
  }
}
```

```java
package org.pumpkinlib.core.led;

import edu.wpi.first.wpilibj.AddressableLED;
import edu.wpi.first.wpilibj.AddressableLEDBuffer;

public final class LedBackends {
  /** The roboRIO can drive exactly ONE AddressableLED at a time. Multiple strips = daisy-chain or Y-cable. */
  public static LedBackend addressable(AddressableLED led, AddressableLEDBuffer buffer);
}
```

```java
package org.pumpkinlib.phoenix6;

import com.ctre.phoenix6.hardware.CANdle;
import org.pumpkinlib.core.led.LedBackend;

/**
 * Renders a resolved LEDPattern buffer onto a CANdle via per-LED SolidColor control requests,
 * and offloads to a device-side animation slot when the resolved pattern is a recognized
 * primitive (solid / strobe / rainbow / larson).  CANdle exposes supply voltage, output current,
 * device temp and 5V-rail/thermal/hardware faults, so the LED controller ITSELF participates in
 * health checks -- a HealthSource comes free.
 *
 * CANdleConfiguration.LED carries StripType (StripTypeValue), BrightnessScalar and
 * LossOfSignalBehavior (verified from CTRE Phoenix 6 26.1.3 docs, 2026-08-06).
 * [UNVERIFIED] the exact per-slot animation request-class set and whether per-LED SolidColor
 * at 50 Hz is CAN-affordable for a 60-LED strip -- MEASURE before shipping; fall back to
 * device-side animations if it is not.
 */
public final class CandleBackend implements LedBackend {
  public static CandleBackend of(CANdle candle, int onboardCount, int stripCount);
  public org.pumpkinlib.core.health.HealthSource health();
  public CANdle raw();
}
```

We map the **common subset** — solid, blink, breathe, scroll, rainbow, progress — and let advanced users drop to the native object. We do not attempt to unify CTRE's 8 animation slots with WPILib's buffer algebra.

Standard state table `PumpkinRobot` installs by default (overridable, and this is a *default*, not a lock):

```java
// MatchContext::isEStopped, never DriverStation::isEStopped — core.led is not on the
// DriverStation allowlist (§5.3 Part A, onlyMatchReadsDriverStation), and this exact line is
// row 8 of the in-domain conversion table in §5.3 Part E.
leds.state(100, RobotHealth::hasError,        LEDPattern.solid(Color.kRed).blink(Seconds.of(0.15)))
    .state( 95, MatchContext::isEStopped,     LEDPattern.solid(Color.kRed))
    .state( 90, RobotHealth::hasWarning,      LEDPattern.solid(Color.kOrange).breathe(Seconds.of(1.0)))
    .state( 20, MatchContext::allianceKnownAndRed,  LEDPattern.solid(Color.kRed))
    .state( 20, MatchContext::allianceKnownAndBlue, LEDPattern.solid(Color.kBlue))
    .idle(LEDPattern.rainbow(255, 128)
              .scrollAtAbsoluteSpeed(MetersPerSecond.of(0.5), Meters.of(1.0 / 60.0)));
```

The pit-visible payoff: **green means `SelfTest` passed and `AlertRegistry.matchReady()` is true.** A student across the shop can see whether the robot is ready.

### 9.4 Dashboard facade — raw NT4 only, plus a properly-vendored ElasticLib

**No PumpkinLib public signature may mention `ShuffleboardTab` or `SmartDashboard`.** Both are deleted in 2027 along with NT3. We publish to raw NT4 under `/Pumpkin/`, which Elastic, Glass, and AdvantageScope all consume simultaneously and which survives the transition.

ElasticLib is currently distributed as *a file you copy into your project*, so every team runs a divergent private copy and fixes never propagate. We vendor it properly in `pumpkinlib-elastic` (BSD-compatible, attributed, upstream-tracked) and wrap it.

```java
package org.pumpkinlib.core.dashboard;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import org.pumpkinlib.core.alert.Severity;

public final class PumpkinDashboard {
  /**
   * Called by PumpkinRobot. Starts the deploy-directory web server on 5800 so Elastic's
   * "Load Layout From Robot" (Ctrl+D) works -- one line nobody ever remembers to add. The path
   * comes from the facade, not from Filesystem directly (§5.3 Part E):
   *   edu.wpi.first.net.WebServer.start(5800, Platform.deployDir().toString());
   * (verified: edu.wpi.first.net.WebServer.start(int port, String path), WPILib 2026;
   *  edu.wpi.first.net is NOT on the confined list, so naming WebServer here is legal.)
   */
  public static void init();

  /** Rate-limited and rising-edge only. Elastic's sendNotification has no dedup; periodic() floods it. */
  public static void notify(Notify n);

  public static void selectTab(String tab);
  public static Trigger tabActive(String tab);
}
```

```java
package org.pumpkinlib.core.dashboard;

import edu.wpi.first.units.measure.Time;
import org.pumpkinlib.core.alert.Severity;

public record Notify(Severity level, String title, String detail, Time show, Time minInterval) {
  public static Notify error(String title, String detail);
  public static Notify warning(String title, String detail);
  public static Notify info(String title, String detail);
  public Notify oncePerSecond();
  public Notify noAutoDismiss();
}
```

We ship, in `layouts/` and copied into the template's deploy root:

* `elastic-layout.json` — a working driver dashboard: match time, alliance, auto chooser, Alerts widget, self-test summary text, battery, CAN utilization, camera streams. **Hand-exported from the Elastic GUI, never generated** — Elastic's layout schema is not published as a stable public contract and generating it is a real risk. **[UNVERIFIED]** whether that schema is stable across the 2026.x line.
* `AdvantageScope.json` and `AdvantageScopeTuning.json` — debug and tuning layouts, so tuning is open-a-file rather than build-a-dashboard.

### 9.5 Haptics and the control map

WPILib gives you exactly `setRumble(RumbleType, double)`: no getter, no duration, no patterns, no arbitration, and no auto-clear on disable. Every team writes the same ad-hoc `startEnd(...).withTimeout(...)`, then two subsystems stomp each other and the rumble is left on at disable.

```java
package org.pumpkinlib.core.hid;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj.GenericHID.RumbleType;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.Command;

public final class Rumble {
  public static Builder on(GenericHID hid);

  public static final class Builder {
    public Builder side(RumbleType t);      // kLeftRumble | kRightRumble | kBothRumble
    public Builder priority(int p);         // highest active wins, per side
    public Command play(RumblePattern p);
  }
}

public final class RumblePattern {
  public static RumblePattern pulse(double intensity, Time duration);
  public static RumblePattern doubleTap();
  public static RumblePattern ramp(double from, double to, Time duration);
  public static RumblePattern sos();
}
```

`RumbleScheduler` zeroes every registered HID on `disabledInit` unconditionally. Left/right are independent, so a team can encode two facts at once ("game piece acquired" left, "aligned to target" right).

```java
package org.pumpkinlib.core.hid;

import java.nio.file.Path;
import java.util.function.Function;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/**
 * Named bindings. `new JoystickButton(board, 7)` is unreadable and impossible to hand to a new
 * operator; elite teams maintain a physical control-map poster, small teams have one student
 * who remembers. Naming the binding once produces the poster for free.
 */
public final class ControlMap<T extends CommandGenericHID> {
  public static <T extends CommandGenericHID> ControlMap<T> of(String role, T hid);

  public ControlMap<T> onTrue(String action, Function<T, Trigger> button, Command c);
  public ControlMap<T> whileTrue(String action, Function<T, Trigger> button, Command c);
  public ControlMap<T> toggleOnTrue(String action, Function<T, Trigger> button, Command c);

  /** Markdown, one table per role. Written by a gradle task into CONTROLS.md. */
  public static String markdown();
  /** Same content to /Pumpkin/Controls for an Elastic text widget. */
  public static void publish();
  public static void writeTo(Path file);

  public T raw();
}
```

`DsMonitor` cross-checks the declared HID ports against `MatchContext.joystickName(port)` / `MatchContext.joystickIsXbox(port)` — **not** `DriverStation.getJoystickName(port)`, which `core.health.builtin` is not allowed to name (§5.3 Part A) — and raises an `ERROR` / `BLOCKS_MATCH` alert naming the expected and the actual controller when a gamepad lands in the wrong slot. That is a documented, repeated field failure, and it is one of the four conditions allowed to reach the driver, because half the bindings being dead genuinely means do not take the field.

---

## 10. FMS / DriverStation Integration

`DriverStation.getAlliance()` returns an **empty `Optional` until the DS connects**. Reading it in a constructor, in `robotInit`, or in an auto-chooser lambda that evaluates early gives you the wrong alliance and a mirrored auto. Nothing in WPILib caches it at the right moment or warns that you read it too early. This is the highest-frequency competition-day failure in FRC and it costs small teams entire matches.

```java
package org.pumpkinlib.core.match;

import java.util.Optional;
import java.util.OptionalInt;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj.DriverStation.MatchType;
import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj2.command.button.Trigger;

/**
 * THE single DriverStation reader in PumpkinLib (§5.3 Part A, onlyMatchReadsDriverStation).
 * Nothing else in org.pumpkinlib — not even org.pumpkinlib.core.compat — may name DriverStation.
 * When 2027 moves or renames the DS surface, this is the one file that changes.
 *
 * Spellings mirror WPILib exactly (isFMSAttached, not isFmsAttached) so that a student who learns
 * this API has learned the WPILib one. "Never hide WPILib" applies to method names too.
 */
public final class MatchContext {

  /** Latched on the DS-connect rising edge. Never empty after the first connect. */
  public static Optional<Alliance> alliance();

  /** Convenience for path flipping. False (blue) until known; allianceKnown() gates it. */
  public static boolean isRed();
  public static boolean isBlue();
  public static boolean allianceKnown();

  /** Latched-AND-known forms, for the default LED state table (§9.3). */
  public static boolean allianceKnownAndRed();
  public static boolean allianceKnownAndBlue();

  /** Driver station 1/2/3. */
  public static OptionalInt station();

  // ---- Attachment ---------------------------------------------------------------------
  public static boolean isFMSAttached();
  public static boolean isDSAttached();

  /**
   * Has this ROBOT ever been FMS-attached, across boots? Backed by
   * Platform.persistentDir()/pumpkin/target-state.json, written on the FMS-attach rising edge.
   * Read by the deploy gate (§7.4) to decide whether a dirty tree blocks. Never throws; a
   * missing or unreadable file is false, because an unreadable file must never block a fix.
   */
  public static boolean everFMSAttached();

  // ---- Enable state. These are the calls that replace DriverStation.* everywhere else. ----
  public static boolean isDisabled();
  public static boolean isEnabled();
  public static boolean isAutonomous();
  public static boolean isTeleop();
  public static boolean isEStopped();

  /**
   * DS "Test" mode in 2026; renamed "Utility" in 2027. Callers say isDiagnostics() and never
   * have to care. This is the ONLY spelling in PumpkinLib — Platform deliberately does not
   * have a diagnosticsMode() twin (§5.3 Part B).
   */
  public static boolean isDiagnostics();

  // ---- HID slot verification, for DsMonitor (§8.5, §9.5) --------------------------------
  /** DriverStation.getJoystickName(port). Empty string when the slot is empty. */
  public static String joystickName(int port);
  public static boolean joystickIsXbox(int port);
  public static boolean joystickConnected(int port);

  /** Event/match identity, present only when FMS supplies it. */
  public static Optional<MatchInfo> match();

  /** Approximate; DO NOT use for control decisions. Exposed because pit displays want it. */
  public static Time matchTimeRemaining();

  public static MatchPhase phase();          // DISABLED, AUTONOMOUS, TELEOP, DIAGNOSTICS, ESTOPPED

  public static Trigger onDsAttach();
  public static Trigger onFmsAttach();
  public static Trigger onAllianceKnown();
  public static Trigger onMatchStart();      // first enable after FMS attach
  public static Trigger onMatchEnd();

  /** "CURIE_Q34_a1b2c3d" — event, match, git sha. Used for log naming. */
  public static String logFileStem();

  /** Optional, offline-only. Reads deploy/pumpkin/schedule.json if present. See §12. */
  public static Optional<MatchInfo> scheduledMatch();

  // ---- Lifecycle. Two methods, two different rates, and the split is deliberate. --------

  /**
   * EVERY loop, called by PumpkinRobot. ~20 us of boolean reads plus edge latching: alliance
   * latch, FMS/DS attach edges, everFMSAttached persistence, phase transitions. This is not a
   * slice because its entire value is catching an edge on the cycle it happens.
   */
  static void periodic();

  /**
   * ONE SLICE of the shared round-robin (§8.3.1) — the derived NT publishing under
   * /Pumpkin/Match/. Registered as SliceScheduler.register("Match", MatchContext::poll).
   * There is no wall-clock gate here and there must never be one.
   */
  static void poll();
}
```

```java
package org.pumpkinlib.core.match;

import edu.wpi.first.wpilibj.DriverStation.MatchType;

public record MatchInfo(String eventName, MatchType matchType, int matchNumber, int replayNumber) {
  /** "Q34", "SF2m1", "P3". */
  public String shortLabel();
}
```

```java
package org.pumpkinlib.core.match;

/**
 * The single place PumpkinLib decides what is unsafe at an event.
 * Domain 08 must consult tunablesLocked() on EVERY tunable read -- DogLog's semantic, copied
 * verbatim, because a stray dashboard edit mid-event is the failure that loses matches.
 */
public final class FmsPolicy {
  public static boolean tunablesLocked();          // true when isFMSAttached()
  public static boolean selfTestAllowed();         // false when FMS-attached and enabled
  public static boolean verboseTelemetryAllowed(); // false when FMS-attached, unless overridden
  public static void allowTunablesAtEvent(boolean yesReally);  // loud, logged, alerts
}
```

Everything PumpkinLib derives from FMS/DS **automatically**, with no user code:

1. **Alliance-aware path flipping.** `AutoBuilder.configure(..., MatchContext::isRed, drive)`. This single substitution eliminates the most common competition-day auto failure. Domain 05 wires it; teams never write the lambda.
2. **Match-aware log naming.** WPILib's `DataLogManager` already renames to `FRC_yyyyMMdd_HHmmss_{event}_{match}.wpilog` on FMS attach, but **AdvantageKit's `WPILOGWriter` does not inherit this** and produces hash names like `akit_6497c321bb716896.wpilog`. `pumpkinlib-advantagekit` renames on the FMS-attach edge to `MatchContext.logFileStem()`. This is a real gap and it costs you the ability to find "the log from Qual 34."
3. **FMS data as signals, not metadata.** AdvantageKit's `Logger.recordMetadata()` is write-once before `Logger.start()`, and FMS data only arrives when the DS connects — so event/match **cannot** be metadata. We log them as normal signals under `/Pumpkin/Match/` and put only build/identity data in metadata.
4. **Tunable lockout** (above).
5. **Self-test lockout** — `SelfTest.runAll()` refuses to schedule while FMS-attached and enabled.
6. **Telemetry tiering** — verbose per-signal logging drops to the competition tier on FMS attach unless explicitly overridden.
7. **Git-dirty alert** — a `WARNING` / `PIT_ONLY` "Deployed code has uncommitted changes" is raised **always**, in the shop and at an event, because §7.4 now lets a dirty build deploy and the alert is the thing that preserves provenance in exchange. `PIT_ONLY` is what keeps it from nagging: it never occupies a driver slot. Only the *Elastic notification* is gated on FMS attach, and only on the rising edge.
8. **Alliance-unknown alert** — `ERROR` / `BLOCKS_MATCH` if the match enables and `allianceKnown()` is still false. Blocking is correct here: every path in the auto is about to be mirrored the wrong way.
9. **Energy/battery reset** — `PowerMonitor.matchEnergy()` resets on the match-start edge, so the per-match number is real.
10. **Rumble/LED policy** — LEDs go alliance-colored when alliance latches; rumble patterns are suppressed while disabled.

---

## 11. Deploy Metadata and Config Backup/Restore

### 11.1 Deploy metadata — "is the code on this robot what is in git?"

`gversion` generates `BuildConstants` with `MAVEN_GROUP`, `MAVEN_NAME`, `VERSION`, `GIT_REVISION`, `GIT_SHA`, `GIT_DATE`, `GIT_BRANCH`, `BUILD_DATE`, `BUILD_UNIX_TIME`, and `DIRTY` (0 clean / 1 uncommitted / −1 error) (verified against the WPILib docs page, 2026-08-06). Teams generate it and then nothing surfaces it.

A vendordep cannot add a Gradle plugin, and `BuildConstants` lives in the *team's* package (`frc.robot`), so PumpkinLib cannot import it. We resolve it reflectively, once, at boot, and degrade cleanly:

```java
package org.pumpkinlib.core.config;

import java.util.Optional;

/** Git provenance of the running code. Never throws; reports UNKNOWN if gversion is not wired. */
public final class DeployInfo {
  public static String gitSha();        // "a1b2c3d" or "UNKNOWN"
  public static String gitBranch();
  public static String buildDate();
  public static Dirty dirty();          // CLEAN | DIRTY | UNKNOWN
  public static String pumpkinVersion();
  public static Optional<Integer> teamNumber();

  /** One line for the pit display and the log header. */
  public static String summary();       // "8793 comp | main@a1b2c3d (dirty) | pumpkinlib 2026.1.0"

  public enum Dirty { CLEAN, DIRTY, UNKNOWN }

  /** Called by PumpkinRobot before the logger starts, so this lands in log metadata. */
  static void resolve(String buildConstantsClassName);   // default "frc.robot.BuildConstants"
}
```

Published to `/Pumpkin/Meta/` and pushed into the telemetry sink's `metadata()` **before** the logger starts. Alerts:

* `WARNING` / `PIT_ONLY` "Deployed code has uncommitted changes (git dirty) — this build cannot be traced to a commit" whenever `dirty() == DIRTY`. **Unconditional**, not gated on FMS attach: §7.4 deliberately lets a dirty build reach the robot, and this alert plus `/Pumpkin/Meta/GitDirty` is the entire consideration we get in return. Gating it on FMS would mean the shop deploy that actually created the untraceable jar is the one that says nothing. `PIT_ONLY` is what makes "always on" affordable — it can never crowd out one of the three driver rows (§8.2.1).
* `MatchContext.onFmsAttach()` additionally fires **one** Elastic notification on the rising edge when dirty, because that is the moment the fact becomes expensive.
* `INFO` / `PIT_ONLY` "Build provenance unavailable — add the gversion plugin (see pumpkinlib.dev/install)" when `UNKNOWN`.

### 11.2 Tuned-config backup and restore

`Preferences` persists on the roboRIO filesystem, **not in git**. A RIO reimage, a robot swap, or a fresh image silently reverts every tuned value with no warning. For a small team with one programmer that is unrecoverable at an event.

```java
package org.pumpkinlib.core.config;

import java.nio.file.Path;
import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

/**
 * Every resolved PumpkinLib config record registers here at construction, and every Tunable
 * registers its live value. Gives one answer to "what did this robot actually run with?".
 */
public final class ConfigRegistry {
  public static <T extends edu.wpi.first.util.struct.StructSerializable> void register(String path, T config);
  public static void registerTunable(String key, java.util.function.DoubleSupplier live, double compiled);
  /** Logs every registered config as a struct at boot -- the introspectability of JSON, with a compiler. */
  public static void publishAll();
}
```

```java
package org.pumpkinlib.core.config;

import java.nio.file.Path;
import java.util.List;
import edu.wpi.first.wpilibj2.command.Command;

public final class ConfigSnapshot {
  /**
   * Writes every Tunable + every WPILib Preference to
   *   <persistentDir>/pumpkin/snapshots/<timestamp>_<robotId>_<gitSha>.json
   * AND to the same file name under deploy/pumpkin/ on the next `pumpkin pull` so it lands in git.
   */
  public static Path take();

  /** Bind to a pit button. Requires MatchContext.isDiagnostics(). */
  public static Command takeCommand();

  /** Reload a snapshot into Preferences and the Tunable store. */
  public static void restore(Path json);

  /** Compares live values to deploy/pumpkin/config-snapshot.json (the committed baseline). */
  public static List<Drift> drift();

  public record Drift(String key, double committed, double live) {}
}
```

At boot, if `drift()` is non-empty, a `WARNING` / `PIT_ONLY` alert lists the first three drifted keys by name: *"3 tuned values differ from the committed snapshot: Elevator/kP 45.0 → 52.5, Shooter/kV …"*. That single alert is what turns "the robot behaves differently than the code says" from a mystery into a fact.

CLI side: `pumpkin pull-config` downloads the newest snapshot from the robot over the same port-5800 web server Elastic uses, drops it into `src/main/deploy/pumpkin/config-snapshot.json`, and prints `git diff`. Tuning at the field becomes a committable artifact.

`pumpkin sync` is the same transport used for the other direction of §7.4: it pulls `pumpkin/target-state.json` (the `everFMSAttached` flag `MatchContext` persists) into `build/pumpkin/` so the deploy gate can read it. Both are best-effort with a 500 ms timeout, and both treat an unreachable robot as "no information", never as a failure — an unreachable robot must never be a reason to refuse to fix the robot.

---

## 12. Scouting, The Blue Alliance, and Statbotics

**Recommendation: this does not belong in a robot-code library. Exclude it.** Three independent, sufficient reasons:

1. **Physics.** During a match the robot is on an isolated FMS field network and cannot reach `thebluealliance.com`. An HTTP client in robot code is dead weight at best.
2. **Loop time.** Any blocking network call in a 20 ms periodic is a brownout-adjacent failure mode, and teams *will* put it in `periodic()`. Shipping the capability is shipping the footgun.
3. **Scope and cadence.** It shares zero dependencies, zero types, and zero lifecycle with robot code. Bundling it inflates the jar, drags an HTTP/JSON stack into the robot classpath, and couples PumpkinLib's release cadence to two third-party web APIs. Better tools already exist (Lookout, Arcbotics, Pre-ScoutingApp).

The **one** defensible robot-adjacent slice is offline:

```java
package org.pumpkinlib.core.match;

/** Reads deploy/pumpkin/schedule.json if present. NO network, NO HTTP dependency, ever. */
public final class MatchSchedule {
  public static void loadFromDeploy();          // called by PumpkinRobot; silently no-ops if absent
  public static java.util.Optional<MatchInfo> nextScheduled();
}
```

That file is produced **before** the event by a separate desktop tool, which we ship as its own repo:

* **`pumpkin-scout`** — separate GitHub repo, separate versioning, desktop/CLI only, never a robot dependency. Pulls TBA APIv3 (`https://www.thebluealliance.com/api/v3`, `X-TBA-Auth-Key` header) and Statbotics EPA, and emits `schedule.json` into the robot project's deploy directory. Built only if there is demand after v1.

We say this explicitly in the README's "What PumpkinLib is not" section, because a "one-stop shop" claim invites the question and a vague answer invites a bad PR.

---

## 13. End-to-End Example

This is the complete platform-layer code a team writes. Mechanisms come from `pumpkin gen` (domain 02); everything below is hand-written and is the entire cost of the platform domain.

**`Robot.java` — 22 lines.**

```java
package frc.robot;

import org.pumpkinlib.core.PumpkinRobot;

public class Robot extends PumpkinRobot {
  private RobotContainer m_container;

  public Robot() {
    // Registers: identity resolution, deploy metadata, alert registry, health monitors
    // (CAN, battery, 5V rail, brownout, loop time, git-dirty, DS), power monitor,
    // match context, Elastic web server on 5800, LED defaults, rumble auto-zero,
    // config drift check, and the /Pumpkin NT namespace. All of it. No arguments.
    super();
    m_container = new RobotContainer();
  }

  @Override
  public void teleopInit() {
    // PumpkinRobot has already done its own teleopInit; super is called for you.
  }
}
```

**`RobotContainer.java` — the platform-relevant parts.**

```java
package frc.robot;

import static edu.wpi.first.units.Units.*;

import edu.wpi.first.wpilibj.PowerDistribution.ModuleType;
import edu.wpi.first.wpilibj2.command.button.CommandXboxController;
import frc.robot.robots.RobotIds;
import frc.robot.subsystems.Arm;          // generated by `pumpkin gen mechanism`
import frc.robot.subsystems.Elevator;     // generated
import frc.robot.subsystems.Drive;        // generated
import org.pumpkinlib.core.hid.ControlMap;
import org.pumpkinlib.core.hid.Rumble;
import org.pumpkinlib.core.hid.RumblePattern;
import org.pumpkinlib.core.health.HealthMonitor;
import org.pumpkinlib.core.match.MatchContext;
import org.pumpkinlib.core.power.PowerMonitor;
import org.pumpkinlib.core.selftest.SelfTest;

public class RobotContainer {
  private final CommandXboxController m_driver = new CommandXboxController(0);

  private final Drive    m_drive;
  private final Elevator m_elevator;
  private final Arm      m_arm;

  public RobotContainer() {
    RobotIds.register();                       // one line: identity + per-robot overlays (§7.3)

    m_drive    = new Drive(RobotIds.kDrive);
    m_elevator = new Elevator(RobotIds.kElevator);
    m_arm      = new Arm(RobotIds.kArm);

    // Health: mechanisms already implement HealthSource, so this is three lines and it is done.
    HealthMonitor.watch(m_drive);
    HealthMonitor.watch(m_elevator);
    HealthMonitor.watch(m_arm);

    // Self-test: mechanisms already implement SelfTestable.
    SelfTest.register(m_drive);
    SelfTest.register(m_elevator);
    SelfTest.register(m_arm);

    // Power: name the channels once, get named logs and breaker alerts forever.
    PowerMonitor.pdh()
        .channel(0,  "Drive FL", 40).channel(1,  "Drive FR", 40)
        .channel(2,  "Drive BL", 40).channel(3,  "Drive BR", 40)
        .channel(10, "Elevator leader", 40)
        .channel(11, "Arm", 30);

    configureBindings();
  }

  private void configureBindings() {
    ControlMap.of("Driver", m_driver)
        .whileTrue("Score L4",   c -> c.rightBumper(), m_elevator.toPreset("L4"))
        .whileTrue("Intake",     c -> c.leftTrigger(), m_arm.intake())
        .onTrue   ("Zero gyro",  c -> c.start(),       m_drive.zeroHeading())
        // one button, whole-robot self-test, green/red list on Elastic
        .onTrue   ("Self test",  c -> c.back(),        SelfTest.runAll())
        .publish();

    // Haptics: driver feels the game piece instead of looking away from the field.
    m_arm.hasGamePiece().onTrue(
        Rumble.on(m_driver.getHID()).side(kLeftRumble).priority(50).play(RumblePattern.doubleTap()));
  }
}
```

**What the team did NOT write, and gets anyway:**

* Robot identity detection, per-robot constants, and a build-time gate against deploying sim config to hardware.
* CAN utilization + error-delta monitoring on every bus, with alerts.
* Battery resting-voltage and sag detection correlated against PDH current.
* 5 V rail fault counting (the "your encoder lied to you" signal).
* Latched brownout detection with a timestamp.
* Git provenance in the log and a "this code is not in git" alert at an event.
* Per-domain loop-time budgets so overruns are attributable.
* Alliance latched on DS connect, with an `ERROR` / `BLOCKS_MATCH` alert if it is still unknown at enable, wired straight into PathPlanner's flip supplier.
* Match-aware WPILOG naming (`CURIE_Q34_a1b2c3d.wpilog`).
* Tunables that go inert on FMS attach.
* A config-drift alert when the robot's tuned values differ from what is committed.
* Elastic layout served from the robot on port 5800.
* Priority-arbitrated LEDs showing robot health, with rumble auto-zeroed on disable.
* A `CONTROLS.md` control-map poster regenerated from the bindings.
* A one-button pre-match self-test with per-step pass/fail on the pit display.
* All of the above working identically in `./gradlew simulateJava` with no robot present.

---

## 14. What We Deliberately Do NOT Do

| We don't build | Because this already does it |
|---|---|
| A logging or replay framework | **AdvantageKit** (replay), **DogLog** (zero-ceremony), **WPILib Epilogue** (`@Logged`). We ship a pluggable `TelemetrySink` and adapters. The ecosystem is genuinely split — 254 and 3061 use AdvantageKit, Spectrum 3847 uses DogLog — and hard-wiring one halves the addressable audience. |
| A driver dashboard | **Elastic**. We vendor ElasticLib properly and ship a layout; we do not write a dashboard. |
| A log viewer / replay UI | **AdvantageScope**. Our job is to make the logs good: `/Pumpkin/…` namespaces and units on every signal. |
| A live-debug UI | **Glass**. It reads the same NT4 keys; nothing to integrate. |
| A pit-display desktop app | Team 135's Squire proves the concept, but a small team should not install a second app. Our self-test reports into Elastic, which they already have open. |
| Anything on Shuffleboard or SmartDashboard | Both **deleted in 2027**, along with NT3. Raw NT4 only. |
| A path planner or trajectory optimizer | **PathPlanner** (2026.1.2) and **Choreo** (2026.0.3). We supply only the alliance-flip supplier and the four-method drivetrain contract. |
| Swerve kinematics or odometry | **WPILib**, **CTRE SwerveDrivetrain**, **YAGSL**. |
| Vision pose estimation | **PhotonVision** (2026.3.4) and **LimelightHelpers** MegaTag2. |
| System identification | **WPILib `SysIdRoutine`** + the bundled SysId tool. |
| Motor control primitives | **Phoenix 6** and **REVLib**. We never wrap on-motor closed loop behind a roboRIO-side controller. |
| Game-piece physics simulation | **maple-sim** — integrated as an *optional* adapter only, because it is beta and carries documented succession risk. Never a required dependency. |
| A whole-robot state machine | **WPILib 2027 Commands v3** ships a declarative state machine API. Building ours would be an immediate, publicly-called-out duplication. |
| Command-group / sequencing utilities | Commands v3's `coroutine.await()` obsoletes most of them. |
| A tunable-number *storage* system independent of WPILib | WPILib 2027 is adding a first-party **Tunable** API. Domain 08's `Tunable` is designed to re-point at it. We own only the FMS-lockout *policy* and the snapshot/restore. |
| A scouting app, TBA client, or Statbotics client in robot code | **Lookout**, **Arcbotics**, **Pre-ScoutingApp**. See §12. |
| C++ or Python bindings | Java is ~90% of FRC and the reason help is available at events. |
| Hardware fault polling at 50 Hz — **or at 4 Hz** | 135 measured full fault checking at ~10 ms/loop, over half a 20 ms budget. Polling everything at 4 Hz does not make that work cheaper, it makes it bursty: a 10 ms spike every 250 ms overruns every twelfth loop, and a wall-clock gate is nondeterministic under replay. We run exactly **one** health slice per loop, round-robin, cycle-counted (§8.3). Flat tail, deterministic replay, ~220 ms full sweep. |

---

## 15. Open Questions

1. **`RobotController.getSerialNumber()` / `getComments()` on SystemCore.** Both are the cleanest identity mechanisms available on roboRIO and neither is confirmed to exist on SystemCore. Mitigation is already in the design (the persistent-file strategy is primary), but if both survive we should re-order the resolution chain to put `getComments()` first for UX. **Re-verify at the 2027 beta.**
2. **The exact `edu.wpi.first.*` → `org.wpilib.*` subpackage map.** Confirmed for `math`, `hal` (→ `org.wpilib.hardware.hal`). **[UNVERIFIED]** for `wpilibj`, `wpilibj2.command`, `units`, `net`, `util`, `networktables`. Every row of `wpi-rename-2027.properties` is a guess until the 2027 beta compiles. CI's dual-compile job is the safety net, but the map must be reviewed by a human before `2027.0.0-beta.1`.
3. **Does the `Alert` group NT path stay at `/SmartDashboard/<Group>` in 2027?** It is an NT3-era location and SmartDashboard is gone. If it moves, Elastic's Alerts widget and ours both need updating. Only `org.pumpkinlib.core.alert` is affected, by design.
4. **Is a per-LED `SolidColor` write to a 60-LED CANdle affordable at 50 Hz on a shared CAN bus?** If not, the CANdle backend must offload to device-side animation slots and accept a reduced expressive subset. **Measure before shipping `CandleBackend`.** 8793's `LEDSubsystem` already found that a *software* strobe beats the device-side `StrobeAnimation` because the device animation can persist past its window — that finding should inform the mapping.
5. **Who owns `Tunable` — domain 08 or platform?** Current split: 08 owns the type and NT plumbing; platform owns `FmsPolicy.tunablesLocked()` and `ConfigSnapshot`. This requires 08 to depend on `pumpkinlib-core`, which is fine, but the reverse dependency (platform's snapshot needs to enumerate live tunables) is currently solved by a registration callback. Confirm with domain 08 that a push-registration API is acceptable rather than a query API.
6. **Does `StructGenerator.genRecord()` support `Measure`-typed record components?** `ConfigRegistry.publishAll()` logging resolved configs as structs depends on it. WPILib's docs do not enumerate supported component types. **Must be tested**; fallback is to log a flattened `double`-only projection of each config.
7. **`vendor-json-repo` listing timing.** Getting into the official picker requires a PR that maintainers merge on their schedule. For the 2027 line we should open the PR against the `2027_alphaN` bundle during beta so we are listed on kickoff day rather than three weeks later.
8. **Maven Central namespace verification for `dev.pumpkinlib`.** Requires owning `pumpkinlib.dev` and a DNS TXT record. If that domain is unavailable, fall back to `io.github.<org>` (Central supports GitHub-org verification) and keep the *package* root `org.pumpkinlib` regardless.
9. **Should `PumpkinRobot` extend `TimedRobot` or `LoggedRobot`?** Extending `LoggedRobot` forces an AdvantageKit dependency into core, which violates §3.3. Current plan: `PumpkinRobot extends TimedRobot`, and `pumpkinlib-advantagekit` ships `PumpkinLoggedRobot extends LoggedRobot` sharing an internal `PumpkinLifecycle` object. Confirm with domain 07 that this duplication is acceptable and that the shared lifecycle object is the right seam.
10. **Commands v3 adapter scope.** v3 is Java-only, coroutine-based, and has a documented footgun (a missing `coroutine.yield()` is uncheckable at compile time; WPILib concedes *"A watchdog will be necessary"*). We do not build on it for v1. Open question: do we ship the missing watchdog — a scheduler wrapper that raises a named alert when a command exceeds its budget without yielding — as a PumpkinLib contribution? It is squarely in our "zero-mystery debugging" mission.

---

## 16. Phasing and Effort

| Phase | Contents | Person-weeks |
|---|---|---|
| **P0 — skeleton** (must precede all other domains) | Gradle layout, `buildSrc` conventions, ArchUnit suite, the `compat` package, `PumpkinRobot` lifecycle, `/Pumpkin` NT namespace + `TelemetrySink` SPI, `pumpkinlib-testkit` headless harness | 1.5 |
| **P1 — distribution** | Vendordep JSONs, GitHub Pages Maven, CI publish, install smoke test on 3 OSes, `PumpkinTemplate` v0 | 1.5 |
| **P2 — compday core** | `PumpkinAlert` + `MatchImpact` + `AlertRegistry` + the 3-row driver mirror, `SliceScheduler`, `HealthSource`/`HealthMonitor`/`RobotHealth`, all eight built-in monitors + `LoopTimeMonitor`, `PumpkinTracer` budgets, `MatchContext` + `FmsPolicy`, `DeployInfo`, `AlertBudgetTest` | 3.0 |
| **P3 — identity & config** | `RobotIdentity` + overlays + `pumpkinCheckDeploy`, `ConfigRegistry`, `ConfigSnapshot` + drift alert, `pumpkin pull-config` | 1.5 |
| **P4 — self-test & pit** | `SelfTestRoutine` DSL, `SelfTest` sequencer, NT publishing, Elastic layout, pit workflow docs | 2.0 |
| **P5 — power/pneumatics/LED/haptics** | `PowerMonitor`, `PowerBudget`, `Pneumatic`/`CompressorPolicy`, `LedController` + both backends, `Rumble`, `ControlMap` | 2.0 |
| **P6 — 2027 seam** | `wpi-rename-2027.properties`, generated-source variant, dual-compile CI, 2027 vendordep line | 1.5 |
| **Total** | | **13.0 person-weeks** |

P0 is on the critical path for every other domain and should start immediately.

### v0.1 scope (what ships first)

P0 + P1 + P2 + P3, targeting WPILib 2026, published as `2026.0.1-beta`. That is a genuinely useful library on its own: install one vendordep, extend `PumpkinRobot`, and get identity resolution, ten health checks, alliance latching, git provenance, and config drift detection with about five lines of team code. P4–P6 follow before the 2027 beta.

---

## Appendix A — Sources for load-bearing claims

* Vendordep schema: `https://raw.githubusercontent.com/wpilibsuite/vendor-json-repo/main/check.py` (fetched 2026-08-06).
* Live 2026 vendordep using `frcYear`: `https://3015rangerrobotics.github.io/pathplannerlib/PathplannerLib.json` (fetched 2026-08-06).
* `WebServer.start(int, String)`: `https://github.wpilib.org/allwpilib/docs/release/java/edu/wpi/first/net/WebServer.html` (fetched 2026-08-06).
* `gversion` snippet and `BuildConstants` field list: `https://docs.wpilib.org/en/stable/docs/software/advanced-gradlerio/deploy-git-data.html` (fetched 2026-08-06).
* `CANBus.getStatus()` → `CANBusStatus{Status, BusUtilization, BusOffCount, TxFullCount, REC, TEC}`: `https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/CANBus.CANBusStatus.html` (fetched 2026-08-06).
* `CANdleConfiguration.LED` carries `StripType`/`BrightnessScalar`/`LossOfSignalBehavior`: `https://api.ctr-electronics.com/phoenix6/stable/java/com/ctre/phoenix6/configs/CANdleConfiguration.html`.
* GitHub Packages requires a token for public Maven artifacts: `https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-apache-maven-registry`.
* 2027 removals: `https://docs.wpilib.org/en/2027/docs/yearly-overview/removed-features.html` and `https://docs.wpilib.org/en/latest/docs/yearly-overview/yearly-changelog.html`.
* Dossiers: `.research/deep-compday.json`, `.research/web-apidesign.json`, `.research/web-ecosystem.json`, `.research/web-smallteam.json`, `.research/web-simtest.json`, `.research/repo-8793.json`.
* User repo conventions (`m_field`, `kConstant`, Java 17, GradleRIO 2026.2.1, Phoenix-6-only, no AdvantageKit, hoot replay, `optimizeBusUtilization` everywhere): `C:/Users/ericj/GitHub/8793-2026-Robot/build.gradle`, `.../src/main/java/frc/robot/Robot.java`, `.../Telemetry.java`, `.../subsystems/LEDSubsystem.java`, `.../CLAUDE.md`.

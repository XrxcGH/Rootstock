package org.pumpkinlib.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.domain.JavaConstructorCall;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.domain.JavaTypeVariable;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The twelve ArchUnit rules of {@code DESIGN.md} section 8, plus the lettered sub-rules 1b, 1c, 9a
 * and 9b.
 *
 * <p><strong>Why these are tests and not a style guide.</strong> Every rule here is a seam that
 * makes some future change cheap. Rule 2 and rule 12 are what make the 2027 {@code
 * edu.wpi.first} &rarr; {@code org.wpilib} port an import rewrite rather than a rewrite. Rule 10 is
 * why {@code MatchContext} exists at all. Rule 8 is what lets {@code org.pumpkinlib.pure} be unit
 * tested on any JVM with no WPILib natives — the property this whole test source set depends on.
 * A seam that is documented but not enforced decays in a single build season.
 *
 * <p><strong>Rules that cannot fire yet are still written.</strong> Rules 6, 7 and 11 target
 * packages ({@code org.pumpkinlib.tuning}, {@code org.pumpkinlib.mechanism}, {@code
 * org.pumpkinlib.hardware}) that do not exist in M1. They are written now, marked {@code
 * allowEmptyShould(true)}, and pass vacuously — so the day the package lands the rule is already
 * watching it. Commenting them out and "adding them later" is how a seam becomes decorative.
 *
 * <p><strong>Numbering.</strong> The count of <em>twelve</em> is the count of numbered rules. 1b,
 * 1c, 9a and 9b are clauses of rules 1 and 9, tested here in the same class, and deliberately
 * lettered so that no document has to change the number twelve.
 *
 * <p>Rule 12 (Java 17, no preview features) is not expressible in ArchUnit — it is a class-file
 * version property, not a type-graph property — so it lives in {@link BytecodeVersionTest}.
 */
@AnalyzeClasses(
    packages = "org.pumpkinlib",
    importOptions = ImportOption.DoNotIncludeTests.class)
public final class ArchitectureTest {

  // =============================================================================================
  // Shared vocabulary
  // =============================================================================================

  /** The eight domain packages rule 9 forbids {@code org.pumpkinlib.core..} from naming. */
  private static final List<String> kForbiddenFromCore =
      List.of(
          "org.pumpkinlib.telemetry",
          "org.pumpkinlib.tuning",
          "org.pumpkinlib.sim",
          "org.pumpkinlib.vision",
          "org.pumpkinlib.drive",
          "org.pumpkinlib.auto",
          "org.pumpkinlib.mechanism",
          "org.pumpkinlib.superstructure");

  /**
   * Rule 1c clause (i): AdvantageKit's driver types. These are the types that decide where the log
   * goes; if they are reachable from twenty packages then R18's fork contingency has a
   * twenty-package surface instead of a four-package one.
   */
  private static final String kAkitDriverTypes =
      "org\\.littletonrobotics\\.junction\\.(Logger|LoggedRobot"
          + "|networktables\\.LoggedNetworkNumber"
          + "|networktables\\.NT4Publisher"
          + "|mechanism\\.LoggedMechanism2d"
          + "|LogFileUtil"
          + "|wpilog\\.WPILOGWriter|wpilog\\.WPILOGReader)(\\$.*)?";

  /** Rule 1c clause (ii): the two schema types, additionally legal in any {@code ..io..} package. */
  private static final String kAkitSchemaTypes =
      "org\\.littletonrobotics\\.junction\\.(LogTable|inputs\\.LoggableInputs)(\\$.*)?";

  private ArchitectureTest() {}

  // =============================================================================================
  // RULE 1 — the vendor fence
  // =============================================================================================

  /**
   * RULE 1. No vendor SDK import outside its own adapter artifact. M1 publishes no adapter
   * artifacts, so the correct scope today is "nowhere in {@code org.pumpkinlib}".
   *
   * <p>{@code org.littletonrobotics} is deliberately <em>absent</em> from this list — decision 3
   * makes AdvantageKit a required core dependency, so an artifact fence around it is meaningless.
   * What replaces it is rule 1c, a package allowlist, and that is weaker enforcement than the
   * compile-classpath boundary it replaces. Named here rather than quietly dropped.
   */
  @ArchTest
  static final ArchRule rule1_noVendorSdkInCore =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.ctre..",
              "com.revrobotics..",
              "org.photonvision..",
              "com.pathplanner..",
              "choreo..",
              "swervelib..")
          .because(
              "vendor SDKs live in adapter artifacts discovered by ServiceLoader. A vendor import "
                  + "in core makes the jar unusable by a team that does not run that vendor, and "
                  + "Class.forName on a string literal is not a substitute for a real seam.");

  /**
   * RULE 1b. No {@code dev.doglog} and no {@code edu.wpi.first.epilogue}, anywhere, ever.
   *
   * <p>There is no backend abstraction to write against — a stray import would be a half-built
   * alternative logging path that nobody tests and that diverges the first time AdvantageKit's
   * replay contract matters. DogLog's fault semantics are adopted as design influence only.
   */
  @ArchTest
  static final ArchRule rule1b_noDogLogAndNoEpilogue =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("dev.doglog..", "edu.wpi.first.epilogue..")
          .because(
              "there is exactly one logging backend (AdvantageKit) and a second half-built one is "
                  + "worse than none: it is the path that is never exercised until it is.");

  /**
   * RULE 1c clause (i). AdvantageKit's driver types are importable from exactly four packages:
   * {@code org.pumpkinlib.telemetry} (the {@code PumpkinLog} facade), {@code org.pumpkinlib.core}
   * (the {@code PumpkinRobot} / {@code PumpkinLifecycle} root — the package itself, not its
   * subpackages), {@code org.pumpkinlib.tuning} and {@code org.pumpkinlib.viz}.
   *
   * <p>Note the allowlist entry {@code "org.pumpkinlib.core"} has no trailing {@code ..}: {@code
   * core.health}, {@code core.alert} and friends are <em>not</em> on it and must publish through
   * the facade.
   */
  @ArchTest
  static final ArchRule rule1c_i_advantageKitDriverTypesAreConfined =
      classes()
          .that()
          .resideOutsideOfPackages(
              "org.pumpkinlib.telemetry..",
              "org.pumpkinlib.core",
              "org.pumpkinlib.tuning..",
              "org.pumpkinlib.viz..")
          .should()
          .onlyDependOnClassesThat()
          .haveNameNotMatching(kAkitDriverTypes)
          .because(
              "rule 1c bounds the blast radius of the R18 fork contingency to four packages. "
                  + "Every other package publishes through PumpkinLog and never touches Logger.")
          .allowEmptyShould(true);

  /**
   * RULE 1c clause (ii). {@code LogTable} and {@code LoggableInputs} are additionally legal in any
   * {@code ..io..} package, because every {@code *Inputs} class in the library implements {@code
   * LoggableInputs} and hand-writes {@code toLog}/{@code fromLog} under D24.
   *
   * <p>This clause is not a loophole — without it, decision 3's own {@code MotorInputs implements
   * LoggableInputs} is a rule violation on day one.
   */
  @ArchTest
  static final ArchRule rule1c_ii_advantageKitSchemaTypesAreConfined =
      classes()
          .that()
          .resideOutsideOfPackages(
              "org.pumpkinlib.telemetry..",
              "org.pumpkinlib.core",
              "org.pumpkinlib.tuning..",
              "org.pumpkinlib.viz..",
              "..io..")
          .should()
          .onlyDependOnClassesThat()
          .haveNameNotMatching(kAkitSchemaTypes)
          .because(
              "LogTable and LoggableInputs are the hand-written IO schema seam (D24) and belong in "
                  + "io packages; anywhere else they are a second, untested publishing path.")
          .allowEmptyShould(true);

  // =============================================================================================
  // RULE 2 — the 2027 seam
  // =============================================================================================

  /**
   * RULE 2. Year-volatile WPILib API is confined to the compat tier.
   *
   * <p>Exactly three packages may name these five types: {@code org.pumpkinlib.core.compat} (the
   * facades), {@code org.pumpkinlib.field} (D14 — it absorbs the 2027 field-origin move, and it is
   * owned by the drive/auto domain but lives inside this same fence) and {@code
   * org.pumpkinlib.core.match} ({@code MatchContext}, the single {@code DriverStation} reader).
   *
   * <p>This is the rule that most easily ships broken, and a rule that is red on day one gets
   * {@code @Disabled} in week two. It is green today because every call site was converted once.
   */
  @ArchTest
  static final ArchRule rule2_volatileWpiApiIsConfined =
      classes()
          .that()
          .resideOutsideOfPackages(
              "org.pumpkinlib.core.compat..", "org.pumpkinlib.field..", "org.pumpkinlib.core.match..")
          .should()
          .onlyDependOnClassesThat()
          .haveNameNotMatching(
              "edu\\.wpi\\.first\\.wpilibj\\.(Timer|RobotController|RobotBase|Filesystem"
                  + "|DriverStation)(\\$.*)?")
          .because(
              "these five types are the ones whose package, class name or method set we expect to "
                  + "move in 2027. Confining them is what makes the port an import rewrite of six "
                  + "files instead of an edit of four hundred.");

  // =============================================================================================
  // RULE 3 — one clock
  // =============================================================================================

  /**
   * RULE 3, first half. No {@code Timer.getFPGATimestamp()} anywhere.
   *
   * <p>Under AdvantageKit replay {@code Timer.getTimestamp()} reads the injected log clock while
   * {@code getFPGATimestamp()} reads the hardware clock. One method name apart, and it is the
   * difference between a replay whose every {@code settleTime} and {@code deadline} lines up and
   * one where they all drift. {@code Clock.seconds()} is the one spelling.
   */
  @ArchTest
  static final ArchRule rule3a_noFpgaTimestamp =
      noClasses()
          .should(callingMethodNamed("getFPGATimestamp"))
          .because(
              "Clock.seconds() is the only clock read in this library. getFPGATimestamp() is not "
                  + "replay-safe and desynchronizes every measured duration under replay.");

  /**
   * RULE 3, second half. No {@code new Timer()} anywhere.
   *
   * <p>A {@code Timer} instance is a wall clock hidden inside an object, which is the same bug
   * wearing a hat: it does not replay and it does not step under {@code SimHooks}. {@code
   * PumpkinStopwatch} is the Clock-backed replacement and has the same shape.
   */
  @ArchTest
  static final ArchRule rule3b_noTimerInstances =
      noClasses()
          .should(constructingType("edu.wpi.first.wpilibj.Timer"))
          .because(
              "an instance Timer is a wall clock in disguise: it neither replays nor steps under "
                  + "SimHooks. Use PumpkinStopwatch, which is Clock-backed and shape-compatible.");

  // =============================================================================================
  // RULE 4 — no dead dashboards
  // =============================================================================================

  /**
   * RULE 4. No {@code SmartDashboard}, {@code Shuffleboard}, {@code ShuffleboardTab} or NT3 in any
   * signature or body.
   *
   * <p>Shuffleboard is unmaintained and SmartDashboard is a global mutable namespace with no
   * schema; both are removed or effectively dead on the 2027 line. Everything PumpkinLib publishes
   * goes through NT4 under {@code /Pumpkin/}, which is the contract the shipped Elastic and
   * AdvantageScope layouts bind to.
   */
  @ArchTest
  static final ArchRule rule4_noDeadDashboards =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "edu.wpi.first.wpilibj.smartdashboard..",
              "edu.wpi.first.wpilibj.shuffleboard..",
              "edu.wpi.first.cameraserver..")
          .because(
              "the /Pumpkin/ NT4 namespace is a contract the shipped dashboard layouts bind to. "
                  + "SmartDashboard is a schema-free global namespace and Shuffleboard is dead.");

  // =============================================================================================
  // RULE 5 — nothing that 2027 removes
  // =============================================================================================

  /**
   * RULE 5, first clause. No HAL type that the 2027 line removes.
   *
   * <p>Referencing any of these guarantees a rewrite rather than a rename, which makes the whole
   * of section 10's "the port is an import rewrite" promise false.
   */
  @ArchTest
  static final ArchRule rule5a_noDeadHalApis =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .haveNameMatching(
              ".*\\.(Relay|AnalogOutput|AnalogGyro|AnalogTrigger|SPI|DMA|Counter|Ultrasonic"
                  + "|DigitalGlitchFilter|Servo|NidecBrushless|ADIS16448_IMU|ADIS16470_IMU"
                  + "|ADXRS450_Gyro|ADXL345_SPI)")
          .because(
              "every one of these is removed on the 2027 line. Using one converts a mechanical "
                  + "import rename into a redesign in January.");

  /**
   * RULE 5, the {@code Mut*} clause. Mutable measures are deleted in 2027 and must never cross a
   * PumpkinLib boundary.
   *
   * <p>Deliberately <em>one</em> predicate matched on the fully-qualified name rather than
   * {@code resideInAPackage("edu.wpi.first.units.measure..").andShould().haveSimpleNameStartingWith("Mut")}:
   * that spelling is a conjunction on a {@code noClasses()} predicate, so it forbids only classes
   * satisfying both clauses. It happens to work today and it <em>reads</em> as a ban on the whole
   * {@code measure} package that every config record depends on — one upstream package move turns
   * the accident into a build-breaking lie.
   */
  @ArchTest
  static final ArchRule rule5b_noMutableUnits =
      noClasses()
          .should()
          .dependOnClassesThat()
          .haveNameMatching("edu\\.wpi\\.first\\.units\\.measure\\.Mut.*")
          .because(
              "mutable measures are deleted in 2027, and a mutable value crossing an API boundary "
                  + "is aliasing that no reader of the signature can see.");

  /**
   * RULE 5, the {@code robotInit} clause, scoped to library packages.
   *
   * <p>No PumpkinLib type overrides {@code IterativeRobotBase.robotInit()} and no PumpkinLib public
   * API declares a method named {@code robotInit}. {@code PumpkinRobot} initialises in its
   * constructor (D13a); {@code PumpkinLifecycle}'s method is {@code init()} (D29).
   *
   * <p>The scope matters: a <em>team's</em> {@code Robot} overriding {@code robotInit()} is legal
   * and unaffected. Revision 3 wrote this rule unscoped and the library's own front door violated
   * it on day one.
   */
  @ArchTest
  static final ArchRule rule5c_noRobotInit =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .haveNameMatching("robotInit")
          .because(
              "constructor initialisation is WPILib's recommended shape and the reason robotInit() "
                  + "was deprecated. PumpkinRobot initialises in its constructor (D13a) and "
                  + "PumpkinLifecycle's hook is init() (D29).");

  // =============================================================================================
  // RULE 6 — the actuator safety property
  // =============================================================================================

  /**
   * RULE 6. No {@code TuningTarget.setVoltage} call outside {@code TuningSupervisor}.
   *
   * <p>This is the safety property that keeps a fourteen-year-old from commanding an arm directly
   * from a dashboard widget: every open-loop voltage during a tuning session goes through the one
   * class that owns the soft limits, the timeout and the enable interlock.
   *
   * <p><strong>Vacuous in M1</strong> — {@code org.pumpkinlib.tuning} does not exist yet. Written
   * now so it is already watching on the day it does.
   */
  @ArchTest
  static final ArchRule rule6_onlyTuningSupervisorSetsVoltage =
      noClasses()
          .that()
          .haveNameNotMatching(".*\\.TuningSupervisor(\\$.*)?")
          .should(callingMethodNamedOnOwnerMatching("setVoltage", ".*\\.TuningTarget(\\$.*)?"))
          .because(
              "open-loop voltage during a tuning session must pass the one class that owns the "
                  + "soft limits, the session timeout and the enable interlock.")
          .allowEmptyShould(true);

  // =============================================================================================
  // RULE 7 — no mandatory inheritance
  // =============================================================================================

  /**
   * RULE 7. No user-extendable abstract class in a public API, except {@code Mechanism} and {@code
   * PumpkinAutoMode}.
   *
   * <p>Commands are returned from factories, never subclassed, which is what makes the Commands v3
   * port an internal swap rather than a breaking change for every team. An abstract class in a
   * public API is a permanent constraint on both the library's evolution and the user's own type
   * hierarchy, and every one of them has to earn its place by name.
   */
  @ArchTest
  static final ArchRule rule7_noUserExtendableAbstractClasses =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .and()
          .areNotAssignableTo(Throwable.class)
          .and()
          .haveNameNotMatching(".*\\.Mechanism(\\$.*)?")
          .and()
          .haveNameNotMatching(".*\\.PumpkinAutoMode(\\$.*)?")
          .should(bePubliclyExtendableAbstractClass())
          .because(
              "requiring `extends` is what makes a library hostile to Commands v3 and to a team's "
                  + "own hierarchy. Everything here is an interface plus a registry. Exactly two "
                  + "abstract classes are allowed and both are named in DESIGN.md section 8.");

  /**
   * RULE 7, the {@code SubsystemBase} corollary from {@code design/06} section 5.3 Part A.
   *
   * <p>135's Consul requires {@code extends SubsystemChecker} and is therefore hostile to Commands
   * v3. PumpkinLib's mechanisms implement the {@code Subsystem} <em>interface</em>; nothing in the
   * library is assignable to {@code SubsystemBase}.
   */
  @ArchTest
  static final ArchRule rule7b_noMandatoryInheritance =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .beAssignableTo("edu.wpi.first.wpilibj2.command.SubsystemBase")
          .because(
              "Mechanism implements the Subsystem interface. Inheriting SubsystemBase would spend "
                  + "the user's single superclass slot and block the Commands v3 swap.");

  // =============================================================================================
  // RULE 8 — the pure tier
  // =============================================================================================

  /**
   * RULE 8. {@code org.pumpkinlib.pure} imports nothing from {@code edu.wpi.first}.
   *
   * <p>Verified by bytecode scan, not by convention. This is the property that lets the maths tier
   * be unit-tested on any JVM with no WPILib natives present — which is exactly how the rest of
   * this test source set runs in CI. It is also why {@code PumpkinMath.deadband2d} returns the
   * HAL-free {@code Vec2} record and not {@code Translation2d}: {@code design/05} declared both
   * "HAL-free" and "returns Translation2d", and those cannot both be true.
   */
  @ArchTest
  static final ArchRule rule8_pureIsHalFree =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib.pure..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("edu.wpi.first..")
          .because(
              "the pure tier must be testable on a bare JVM. This is a bytecode property, not a "
                  + "naming convention, and it is what keeps the maths honest.");

  // =============================================================================================
  // RULE 9 / 9a / 9b — core points at nothing
  // =============================================================================================

  /**
   * RULE 9, measured per 9a and 9b. No type in {@code org.pumpkinlib.core..} <em>declares</em> a
   * field, parameter, return type, type argument, {@code implements}/{@code extends} clause or
   * thrown type in {@code telemetry}, {@code tuning}, {@code sim}, {@code vision}, {@code drive},
   * {@code auto}, {@code mechanism} or {@code superstructure}.
   *
   * <p><strong>9a</strong> says what "depend on" includes: naming a type in a signature counts
   * exactly as loudly as calling a method. That is the clause that moved {@code LogConfig},
   * {@code RobotMode} and {@code Tier} into {@code org.pumpkinlib.core.spi} (D31, D33) rather than
   * adding rule 9's first named exception.
   *
   * <p><strong>9b</strong> says what it is measured <em>over</em>: SIGNATURES, not bytecode.
   * Deliberately not {@code classes().should().onlyDependOnClassesThat()} — that is the bytecode
   * form, it is strictly stronger, and it is unsatisfiable today because {@code
   * PumpkinRegistry.addAll(Object...)} routes registrants by {@code instanceof}, which crosses in
   * the compiled body while declaring nothing but {@code Object...}. A narrower rule that is green
   * and enforced beats a wider rule that is {@code @Disabled} by week two. If a future pass wants
   * the bytecode form the fix is structural and already named: invert the route so telemetry
   * registers itself against a core-owned {@code core.spi} hook, the way {@code LifecycleHook}
   * already works.
   *
   * <p>{@code org.pumpkinlib.core.spi} is the one downward edge, and it points <em>into</em> core:
   * it holds the value types the outer domains must name.
   */
  @ArchTest
  static final ArchRule rule9_coreDeclaresNothingFromOuterDomains =
      classes()
          .that()
          .resideInAPackage("org.pumpkinlib.core..")
          .should(declareNoSignatureTypeIn(kForbiddenFromCore))
          .because(
              "D26 made mechanical. Core is the tier every other domain compiles against; an arrow "
                  + "out of it is what would break first if the artifact split D28 keeps as a "
                  + "zero-cost option were ever re-published.");

  /**
   * RULE 9, the member half, spelled the way {@code DESIGN.md} 9b asks for it — over {@code
   * members().that().areDeclaredInClassesThat().resideInAPackage("org.pumpkinlib.core..")}.
   *
   * <p>This overlaps {@link #rule9_coreDeclaresNothingFromOuterDomains} on purpose. That rule reads
   * the whole class including its supertypes and type parameters; this one is the literal
   * member-level spelling the design specifies, so a reader comparing the document to the code
   * finds the sentence they were promised.
   */
  @ArchTest
  static final ArchRule rule9b_coreMembersDeclareNothingFromOuterDomains =
      methods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAPackage("org.pumpkinlib.core..")
          .should(declareNoMemberTypeIn(kForbiddenFromCore))
          .because(
              "9b: rule 9 governs the public shape of core — what a reader of core's API sees and "
                  + "what a re-published artifact split would break on — not every bytecode touch.");

  // =============================================================================================
  // RULE 10 — one DriverStation reader
  // =============================================================================================

  /**
   * RULE 10. Only {@code org.pumpkinlib.core.match..} may name {@code
   * edu.wpi.first.wpilibj.DriverStation}. Everything else goes through {@code MatchContext}.
   *
   * <p>This one is absolute — it is why {@code MatchContext} exists. Note the allowlist is
   * <em>narrower</em> than rule 2's: {@code core.compat} is not on it either. That is why {@code
   * Platform} has no {@code diagnosticsMode()}; it would have made compat a second reader, and a
   * "single reader" rule with two readers is a lie. {@code MatchContext.isDiagnostics()} is the one
   * spelling.
   *
   * <p>If a second package ever needs the DriverStation, the answer is a new {@code MatchContext}
   * method, not a new allowlist entry.
   */
  @ArchTest
  static final ArchRule rule10_onlyMatchReadsDriverStation =
      classes()
          .that()
          .resideOutsideOfPackages("org.pumpkinlib.core.match..")
          .should()
          .onlyDependOnClassesThat()
          .haveNameNotMatching("edu\\.wpi\\.first\\.wpilibj\\.DriverStation.*")
          .because(
              "one reader means one place where alliance, FMS attach and match time are latched, "
                  + "which is what makes those values consistent within a single loop and "
                  + "substitutable in a test.");

  // =============================================================================================
  // RULE 11 — mechanisms do not throw
  // =============================================================================================

  /**
   * RULE 11. No explicit {@code throw} in {@code org.pumpkinlib.mechanism..} or {@code
   * org.pumpkinlib.hardware..} outside constructors, static factories and {@code Validation}.
   *
   * <p>Nothing thrown from a {@code periodic()} may take the robot loop with it: a validation
   * mistake must be a value collected into SAFE_MODE at boot, not an exception at 40 seconds into a
   * match. Construction time is different — that is boot, and a fatal config error there is
   * <em>supposed</em> to stop the robot before it moves.
   *
   * <p>Approximated over bytecode as "instantiates a {@code Throwable}", because ATHROW itself is
   * not in ArchUnit's model and you cannot throw what you did not construct.
   *
   * <p><strong>Vacuous in M1</strong> — neither package exists yet.
   */
  @ArchTest
  static final ArchRule rule11_mechanismsDoNotThrowOutsideConstruction =
      noMethods()
          .that()
          .areDeclaredInClassesThat()
          .resideInAnyPackage("org.pumpkinlib.mechanism..", "org.pumpkinlib.hardware..")
          .and()
          .areNotStatic()
          .and()
          .areDeclaredInClassesThat()
          .haveNameNotMatching(".*\\.Validation(\\$.*)?")
          .should(constructingAThrowable())
          .because(
              "a diagnostic must never become the outage. Config errors are values collected into "
                  + "SAFE_MODE at boot; periodic() never throws.")
          .allowEmptyShould(true);

  // =============================================================================================
  // Custom conditions
  // =============================================================================================

  private static ArchCondition<JavaClass> callingMethodNamed(String methodName) {
    return new ArchCondition<>("call any method named " + methodName + "()") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
          if (call.getTarget().getName().equals(methodName)) {
            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> callingMethodNamedOnOwnerMatching(
      String methodName, String ownerRegex) {
    return new ArchCondition<>(
        "call " + methodName + "() on an owner matching " + ownerRegex) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
          if (call.getTarget().getName().equals(methodName)
              && call.getTargetOwner().getName().matches(ownerRegex)) {
            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaClass> constructingType(String typeName) {
    return new ArchCondition<>("construct " + typeName) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
          if (call.getTargetOwner().getName().equals(typeName)) {
            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
          }
        }
      }
    };
  }

  private static ArchCondition<JavaMethod> constructingAThrowable() {
    return new ArchCondition<>("construct a Throwable") {
      @Override
      public void check(JavaMethod item, ConditionEvents events) {
        for (JavaConstructorCall call : item.getConstructorCallsFromSelf()) {
          if (call.getTargetOwner().isAssignableTo(Throwable.class)) {
            events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
          }
        }
      }
    };
  }

  /**
   * True for a class that a user could be forced to {@code extend}: public, abstract, not an
   * interface, not an enum, not a record, and with at least one constructor a subclass outside the
   * package could reach.
   */
  private static ArchCondition<JavaClass> bePubliclyExtendableAbstractClass() {
    return new ArchCondition<>("be a public, user-extendable abstract class") {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        boolean extendable =
            item.getModifiers().contains(JavaModifier.PUBLIC)
                && item.getModifiers().contains(JavaModifier.ABSTRACT)
                && !item.isInterface()
                && !item.isEnum()
                && !item.isRecord()
                && item.getConstructors().stream()
                    .anyMatch(
                        c ->
                            c.getModifiers().contains(JavaModifier.PUBLIC)
                                || c.getModifiers().contains(JavaModifier.PROTECTED));
        if (extendable) {
          events.add(
              SimpleConditionEvent.satisfied(
                  item,
                  item.getName()
                      + " is a public abstract class with a subclass-reachable constructor"));
        }
      }
    };
  }

  /**
   * Rule 9 over a whole class: supertypes, type parameters, and every member's declared types.
   *
   * @param forbiddenPackages package prefixes no declared type may live in
   * @return the condition
   */
  private static ArchCondition<JavaClass> declareNoSignatureTypeIn(List<String> forbiddenPackages) {
    return new ArchCondition<>(
        "declare no field, parameter, return type, type argument, supertype or thrown type in "
            + forbiddenPackages) {
      @Override
      public void check(JavaClass item, ConditionEvents events) {
        List<String> offences = new ArrayList<>();

        item.getRawSuperclass()
            .ifPresent(s -> collect(s.getName(), "extends " + s.getName(), forbiddenPackages, offences));
        for (JavaClass i : item.getRawInterfaces()) {
          collect(i.getName(), "implements " + i.getName(), forbiddenPackages, offences);
        }
        for (JavaTypeVariable<JavaClass> tv : item.getTypeParameters()) {
          for (JavaType bound : tv.getUpperBounds()) {
            for (String n : namesOf(bound)) {
              collect(n, "type parameter <" + tv.getName() + " extends " + n + ">", forbiddenPackages, offences);
            }
          }
        }
        for (JavaCodeUnit unit : item.getCodeUnits()) {
          offences.addAll(memberOffences(unit, forbiddenPackages));
        }
        for (var field : item.getFields()) {
          for (String n : namesOf(field.getType())) {
            collect(n, "field " + field.getName() + " : " + n, forbiddenPackages, offences);
          }
        }

        for (String offence : offences) {
          events.add(SimpleConditionEvent.violated(item, item.getName() + " declares " + offence));
        }
      }
    };
  }

  /** Rule 9b in its literal member-level spelling. */
  private static ArchCondition<JavaMethod> declareNoMemberTypeIn(List<String> forbiddenPackages) {
    return new ArchCondition<>(
        "declare no parameter, return type, type argument or thrown type in " + forbiddenPackages) {
      @Override
      public void check(JavaMethod item, ConditionEvents events) {
        for (String offence : memberOffences(item, forbiddenPackages)) {
          events.add(
              SimpleConditionEvent.violated(
                  item, item.getFullName() + " declares " + offence));
        }
      }
    };
  }

  private static List<String> memberOffences(JavaCodeUnit unit, List<String> forbiddenPackages) {
    List<String> offences = new ArrayList<>();
    if (unit instanceof JavaMethod method) {
      for (String n : namesOf(method.getReturnType())) {
        collect(n, "return type " + n + " on " + method.getName() + "()", forbiddenPackages, offences);
      }
    }
    for (JavaType p : unit.getParameterTypes()) {
      for (String n : namesOf(p)) {
        collect(n, "parameter " + n + " on " + unit.getName() + "()", forbiddenPackages, offences);
      }
    }
    for (JavaClass t : unit.getExceptionTypes()) {
      collect(t.getName(), "thrown type " + t.getName(), forbiddenPackages, offences);
    }
    return offences;
  }

  private static void collect(
      String typeName, String description, List<String> forbiddenPackages, List<String> out) {
    for (String forbidden : forbiddenPackages) {
      if (typeName.equals(forbidden) || typeName.startsWith(forbidden + ".")) {
        out.add(description);
        return;
      }
    }
  }

  /** Erasure plus every actual type argument, recursively — so {@code List<Foo>} yields both. */
  private static Set<String> namesOf(JavaType type) {
    Set<String> names = new LinkedHashSet<>();
    walk(type, names, 0);
    return names;
  }

  private static void walk(JavaType type, Set<String> out, int depth) {
    if (type == null || depth > 8) {
      return;
    }
    out.add(type.toErasure().getName());
    if (type instanceof JavaParameterizedType parameterized) {
      for (JavaType argument : parameterized.getActualTypeArguments()) {
        walk(argument, out, depth + 1);
      }
    }
  }

  /** Unused placeholder kept so the predicate import stays honest if a rule is re-spelled. */
  @SuppressWarnings("unused")
  private static DescribedPredicate<JavaClass> anyClass() {
    return DescribedPredicate.alwaysTrue();
  }
}

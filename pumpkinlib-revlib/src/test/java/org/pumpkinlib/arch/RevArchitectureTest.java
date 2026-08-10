package org.pumpkinlib.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.util.ArrayList;
import java.util.List;

/**
 * ArchUnit rule 1, measured from inside the REVLib adapter.
 *
 * <p>The mirror image of the Phoenix artifact's test, and the one that matters most for the design's
 * stated grievance: {@code DESIGN.md} criticises YAGSL's vendordep precisely because it forces
 * Phoenix 5 onto REV-only teams. This artifact's classpath is core plus the REV adapter plus REVLib,
 * with no Phoenix anywhere — so {@link #theRevAdapterNamesNoCtreType} is not a formality, it is the
 * statement of the whole reason the adapters are separate artifacts.
 *
 * <p>{@link #theAdapterIsPresentAndReallyUsesRevLib} is the non-vacuity guard: without it, a package
 * rename would turn every rule below green over an empty set.
 */
@AnalyzeClasses(packages = "org.pumpkinlib", importOptions = ImportOption.DoNotIncludeTests.class)
public final class RevArchitectureTest {

  /** The one package in this artifact allowed to name a REV type. */
  private static final String kAdapterPackage = "org.pumpkinlib.hardware.rev";

  private RevArchitectureTest() {}

  /**
   * RULE 1, this artifact's half: no {@code com.ctre} anywhere on this side of the fence.
   *
   * <p>A REV-only team installs this vendordep and must not acquire Phoenix as a transitive
   * dependency. That is the concrete complaint the artifact split exists to answer.
   */
  @ArchTest
  static final ArchRule theRevAdapterNamesNoCtreType =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.ctre..")
          .because(
              "dev.pumpkinlib:pumpkinlib-revlib exists so that a REV-only team never installs "
                  + "Phoenix. This is the exact criticism DESIGN.md levels at YAGSL's vendordep, "
                  + "and it costs nothing to keep true and everything to lose.");

  /**
   * RULE 1, the confinement clause: {@code com.revrobotics} is legal in {@code
   * org.pumpkinlib.hardware.rev} and nowhere else.
   *
   * <p>Checked from a classpath where REVLib is actually present, which the core artifact's own test
   * cannot do — the moment a class carrying a REV import moves from this artifact into core, this
   * rule sees it.
   */
  @ArchTest
  static final ArchRule revIsConfinedToTheAdapterPackage =
      noClasses()
          .that()
          .resideOutsideOfPackage(kAdapterPackage + "..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.revrobotics..")
          .because(
              "every REV type this library touches lives behind a PumpkinLib interface in "
                  + kAdapterPackage
                  + ". Core never sees a SparkMax in a signature.");

  /** RULE 1, the rest of the list: no other vendor SDK rides along with the REV adapter. */
  @ArchTest
  static final ArchRule noOtherVendorSdkRidesAlong =
      noClasses()
          .that()
          .resideInAPackage("org.pumpkinlib..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.photonvision..", "com.pathplanner..", "choreo..", "swervelib..", "dev.doglog..")
          .because(
              "each of those is its own artifact behind its own vendordep, at its own milestone. "
                  + "A stray import here installs it for every REV team.");

  /**
   * The non-vacuity guard, and the positive half of the fence.
   *
   * @param classes the imported classes, supplied by ArchUnit
   */
  @ArchTest
  static void theAdapterIsPresentAndReallyUsesRevLib(JavaClasses classes) {
    for (String required :
        List.of(
            "org.pumpkinlib.hardware.MotorIO",
            "org.pumpkinlib.hardware.rev.SparkMotorIO",
            "org.pumpkinlib.hardware.rev.RevGainSink")) {
      if (!classes.contain(required)) {
        throw new AssertionError(
            "ArchUnit imported no class named "
                + required
                + ", so every rule in this file is passing over the wrong set of classes.");
      }
    }

    List<String> revUsers = new ArrayList<>();
    for (JavaClass clazz : classes) {
      boolean touchesRev =
          clazz.getDirectDependenciesFromSelf().stream()
              .anyMatch(d -> d.getTargetClass().getName().startsWith("com.revrobotics."));
      if (touchesRev) {
        revUsers.add(clazz.getName());
      }
    }
    if (revUsers.isEmpty()) {
      throw new AssertionError(
          "no class references com.revrobotics at all, which means revIsConfinedToTheAdapterPackage "
              + "is green for the wrong reason. The REV adapter must actually use REVLib.");
    }
  }
}

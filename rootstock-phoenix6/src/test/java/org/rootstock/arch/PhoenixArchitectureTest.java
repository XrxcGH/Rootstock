package org.rootstock.arch;

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
 * ArchUnit rule 1, measured from inside the Phoenix 6 adapter.
 *
 * <p>The core artifact's own {@code ArchitectureTest} can only prove that <em>core</em> names no
 * vendor, because the adapters are not on its classpath. This artifact's classpath contains core
 * <b>and</b> the Phoenix adapter <b>and</b> the Phoenix SDK — and, critically, no REVLib at all. So
 * this is the vantage point from which the other half of rule 1 is checkable:
 *
 * <ul>
 *   <li>{@code com.ctre} appears only inside {@code org.rootstock.hardware.phoenix} — never in a
 *       core package that happens to be visible from here;
 *   <li>{@code com.revrobotics} appears nowhere. A Phoenix-only team installs this vendordep and
 *       must not acquire REVLib as a transitive dependency, which is exactly the complaint
 *       {@code DESIGN.md} makes about YAGSL forcing Phoenix 5 onto REV-only teams;
 *   <li>no other vendor SDK — PhotonVision, PathPlanner, Choreo, YAGSL — has crept in either.
 * </ul>
 *
 * <p>Every rule here is scoped to {@code org.rootstock..} so the imported set is the two Rootstock
 * artifacts and nothing else. {@link #theAdapterIsPresentAndReallyUsesPhoenix} is the non-vacuity
 * guard: without it, a package rename would turn all four rules green over an empty set.
 */
@AnalyzeClasses(packages = "org.rootstock", importOptions = ImportOption.DoNotIncludeTests.class)
public final class PhoenixArchitectureTest {

  /** The one package in this artifact allowed to name a CTRE type. */
  private static final String kAdapterPackage = "org.rootstock.hardware.phoenix";

  private PhoenixArchitectureTest() {}

  /**
   * RULE 1, this artifact's half: no {@code com.revrobotics} anywhere on this side of the fence.
   *
   * <p>A Phoenix team's robot must not link REVLib because it depends on Rootstock's Phoenix
   * adapter. This is the property that makes the two adapters genuinely separable artifacts rather
   * than one artifact with two source folders.
   */
  @ArchTest
  static final ArchRule thePhoenixAdapterNamesNoRevType =
      noClasses()
          .that()
          .resideInAPackage("org.rootstock..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.revrobotics..")
          .because(
              "dev.rootstock:rootstock-phoenix6 exists so that a Phoenix team never installs "
                  + "REVLib. One import here and the artifact split has bought nothing.");

  /**
   * RULE 1, the confinement clause: {@code com.ctre} is legal in {@code
   * org.rootstock.hardware.phoenix} and nowhere else.
   *
   * <p>This re-checks the core artifact from a classpath where CTRE is actually present, which the
   * core's own test cannot do. A CTRE reference that leaked into, say, {@code
   * org.rootstock.hardware} would be invisible to core's own rule 1 — that jar simply would not
   * compile — but it <i>is</i> visible here the moment somebody moves a class between artifacts.
   */
  @ArchTest
  static final ArchRule ctreIsConfinedToTheAdapterPackage =
      noClasses()
          .that()
          .resideOutsideOfPackage(kAdapterPackage + "..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.ctre..")
          .because(
              "every CTRE type this library touches lives behind a Rootstock interface in "
                  + kAdapterPackage
                  + ". Core never sees a TalonFX in a signature.");

  /** RULE 1, the rest of the list: no other vendor SDK rides along with the Phoenix adapter. */
  @ArchTest
  static final ArchRule noOtherVendorSdkRidesAlong =
      noClasses()
          .that()
          .resideInAPackage("org.rootstock..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.photonvision..", "com.pathplanner..", "choreo..", "swervelib..", "dev.doglog..")
          .because(
              "each of those is its own artifact behind its own vendordep, at its own milestone. "
                  + "A stray import here installs it for every Phoenix team.");

  /**
   * The non-vacuity guard, and the positive half of the fence.
   *
   * <p>Asserts that the imported set really does contain both artifacts — the core seam and the
   * Phoenix backend — and that the Phoenix backend really does reference CTRE types. A rule that
   * forbids {@code com.ctre} outside one package means nothing if nothing anywhere references {@code
   * com.ctre}.
   *
   * @param classes the imported classes, supplied by ArchUnit
   */
  @ArchTest
  static void theAdapterIsPresentAndReallyUsesPhoenix(JavaClasses classes) {
    for (String required :
        List.of(
            "org.rootstock.hardware.MotorIO",
            "org.rootstock.hardware.phoenix.TalonFXMotorIO",
            "org.rootstock.hardware.phoenix.Phoenix6GainSink")) {
      if (!classes.contain(required)) {
        throw new AssertionError(
            "ArchUnit imported no class named "
                + required
                + ", so every rule in this file is passing over the wrong set of classes.");
      }
    }

    List<String> ctreUsers = new ArrayList<>();
    for (JavaClass clazz : classes) {
      boolean touchesCtre =
          clazz.getDirectDependenciesFromSelf().stream()
              .anyMatch(d -> d.getTargetClass().getName().startsWith("com.ctre."));
      if (touchesCtre) {
        ctreUsers.add(clazz.getName());
      }
    }
    if (ctreUsers.isEmpty()) {
      throw new AssertionError(
          "no class references com.ctre at all, which means ctreIsConfinedToTheAdapterPackage is "
              + "green for the wrong reason. The Phoenix adapter must actually use Phoenix.");
    }
  }
}

package org.rootstock.superstructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Subsystem;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rootstock.config.Validation;
import org.rootstock.core.RootstockRegistry;
import org.rootstock.core.SafeMode;
import org.rootstock.example.RobotContainer;
import org.rootstock.example.ScoringState;

/**
 * {@code request(goal)} requires the <b>superstructure alone</b> — never the mechanisms it moves.
 *
 * <p>This is the structural fix for what PathPlanner's own documentation calls the single most
 * common autonomous failure: <i>"my auto stops halfway"</i>. The mechanism is worth stating plainly,
 * because the bug is invisible until it costs a match.
 *
 * <p>A path-following command requires the drivetrain. A scoring command requires the elevator and
 * the arm. Compose them in parallel and everything works — until a second command, scheduled from a
 * trigger or a later branch of the same auto, also requires the elevator. WPILib's scheduler
 * resolves that by <b>interrupting</b> the first command, and the auto silently stops partway
 * through with no error anywhere. Teams lose whole matches to this and usually blame the path.
 *
 * <p>Rootstock makes it unreachable rather than documented: the {@link Superstructure} declares the
 * union requirement <b>exactly once</b>, at construction, and coordinated mechanisms do <b>not</b>
 * self-register with the scheduler. A goal request is therefore a single-subsystem command, and two
 * goal requests contend with each other — which is correct and intended — rather than with whatever
 * else happens to be moving the same mechanism.
 *
 * <p>If this test ever fails, the failure will not look like a test failure. It will look like an
 * auto that stops halfway.
 *
 * <p><b>Tagged {@code @Tag("hal")}.</b> {@link Superstructure} and every {@code Mechanism} construct
 * {@code RootstockAlert}s, which reach NetworkTables through the WPILib JNI. Without natives WPILib's
 * loader calls {@code System.exit(1)} rather than throwing. Run with {@code ./gradlew halTest}.
 */
@Tag("hal")
final class UnionRequirementTest {

  /**
   * Global state this test depends on, cleared before every case.
   *
   * <p>{@code SafeMode} is a static, the tests share one JVM, and {@code Superstructure.request}
   * returns a requirement-free refusal while it is active. So any earlier test that builds a
   * deliberately invalid config to check the error path leaves this one asserting on the refusal
   * instead of on the real command, and the failure reads as "requires 0 subsystems" with nothing
   * pointing at the cause.
   *
   * <p>This was latent until the config validation pipeline was wired up. Before that,
   * {@code Validation.install()} had no call site, nothing ever reached {@code SafeMode.enter}, and
   * a test could depend on the global default without noticing. Fixing the dead pipeline made every
   * such dependency real at once: these three cases passed alone and failed in the suite.
   */
  @BeforeEach
  void clearGlobalConfigState() {
    SafeMode.resetForTest();
    Validation.resetForTest();
    // The registry too, and this is the one that actually bites. Each case here builds a whole
    // RobotContainer, so the second one registers a mechanism already named "Elevator" and the
    // duplicate-name cross-check calls that fatal, correctly. Measured while diagnosing: reset
    // SafeMode alone and construction puts it straight back, 5 faults where an isolated build has
    // 4 and none of them fatal.
    RootstockRegistry.resetForTest();
  }

  private static Superstructure<ScoringState> superstructure() {
    return new RobotContainer().superstructure();
  }

  @Nested
  @DisplayName("the requirement set of a goal request")
  final class RequirementSet {

    @Test
    @DisplayName("is exactly one subsystem")
    void isExactlyOneSubsystem() {
      Superstructure<ScoringState> superstructure = superstructure();

      Command request = superstructure.request(ScoringState.L4);
      Set<Subsystem> requirements = request.getRequirements();

      assertEquals(
          1,
          requirements.size(),
          "request() requires "
              + requirements.size()
              + " subsystems. Every extra one is a chance for the scheduler to interrupt a running "
              + "auto, which is exactly the 'my auto stops halfway' failure this design removes. "
              + "Requirements were: "
              + requirements);
    }

    @Test
    @DisplayName("is the superstructure itself")
    void isTheSuperstructureItself() {
      Superstructure<ScoringState> superstructure = superstructure();

      Command request = superstructure.request(ScoringState.L4);

      assertSame(
          superstructure,
          request.getRequirements().iterator().next(),
          "the single requirement must be the superstructure; anything else means goal dispatch "
              + "contends with mechanism-level commands");
    }

    @Test
    @DisplayName("never contains a coordinated mechanism, for any state")
    void neverContainsACoordinatedMechanism() {
      Superstructure<ScoringState> superstructure = superstructure();
      Set<Subsystem> mechanisms =
          Set.of(RobotContainer.elevator(), RobotContainer.arm(), RobotContainer.roller());

      // Sweep every state, not just the interesting one: a single state that leaks a mechanism
      // requirement is enough to break one auto branch, and it would be found in a match.
      for (ScoringState state : ScoringState.values()) {
        Set<Subsystem> requirements = superstructure.request(state).getRequirements();

        for (Subsystem mechanism : mechanisms) {
          assertFalse(
              requirements.contains(mechanism),
              "request("
                  + state
                  + ") requires a coordinated mechanism directly. A second command touching that "
                  + "same mechanism will interrupt this one and the auto will stop partway with no "
                  + "error reported anywhere.");
        }
      }
    }
  }

  @Nested
  @DisplayName("two goal requests contend with each other, which is the intended behaviour")
  final class Contention {

    @Test
    @DisplayName("requests for different states share the same single requirement")
    void requestsShareTheSameRequirement() {
      Superstructure<ScoringState> superstructure = superstructure();

      Set<Subsystem> l4 = superstructure.request(ScoringState.L4).getRequirements();
      Set<Subsystem> intake = superstructure.request(ScoringState.INTAKE).getRequirements();

      assertEquals(
          l4,
          intake,
          "two goal requests must contend on the same single subsystem, so the newer request wins "
              + "cleanly instead of two half-applied goals fighting inside the mechanisms");
      assertEquals(1, l4.size());
    }
  }

  @Nested
  @DisplayName("the refusal paths keep the same requirement shape")
  final class Refusals {

    @Test
    @DisplayName("request(null) still requires nothing extra")
    void nullRequestDoesNotWidenRequirements() {
      Superstructure<ScoringState> superstructure = superstructure();

      // A refusal command must not quietly acquire a broader requirement set than the happy path;
      // that would make a misconfigured robot interrupt autos that a working robot would not.
      Set<Subsystem> requirements = superstructure.request(null).getRequirements();

      assertTrue(
          requirements.size() <= 1,
          "a refusal command acquired " + requirements.size() + " requirements: " + requirements);
    }
  }
}

package org.rootstock.core.hid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.wpi.first.wpilibj2.command.button.CommandGenericHID;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link ControlMap} — and D30: <strong>a map without a {@code MANUAL} mode is structurally
 * impossible.</strong>
 *
 * <p>The rationale is not a style preference. Automation without a manual mode loses matches: a
 * single-button macro that depends on vision will fail when a tag is occluded by a defender, and if
 * there is no fallback the robot is dead for the match. So D30 gives the library two shapes and this
 * class pins the difference between them:
 *
 * <ul>
 *   <li>{@link ControlMap#withManual} takes the manual bindings as a <em>required argument</em>. The
 *       defect is unrepresentable — you cannot call it and forget.
 *   <li>{@link ControlMap#of} plus a forgotten {@code .mode("MANUAL", ...)} is a defect that can only
 *       be reported after the fact, so {@link ControlMap#manualModeDefect()} reports it, by name,
 *       with the fix.
 * </ul>
 *
 * <p><strong>No natives.</strong> These tests declare modes with empty binding blocks. Registering
 * an actual button would construct a {@code Trigger}, which reaches the {@code CommandScheduler} and
 * the HAL; what is under test here is the mode structure, not button dispatch. {@code selectMode}
 * is likewise avoided because it publishes the active mode to NetworkTables.
 */
final class ControlMapTest {

  private CommandGenericHID m_driver;
  private CommandGenericHID m_operator;

  @BeforeEach
  void freshRegistry() {
    ControlMap.resetForTest();
    m_driver = new CommandGenericHID(0);
    m_operator = new CommandGenericHID(1);
  }

  @AfterEach
  void clearRegistry() {
    ControlMap.resetForTest();
  }

  @Nested
  @DisplayName("withManual — the defect is unrepresentable")
  final class WithManual {

    @Test
    void withManualDeclaresManualAndReportsNoDefect() {
      ControlMap<CommandGenericHID> map = ControlMap.withManual("Driver", m_driver, m -> {});

      assertTrue(map.modes().contains(ControlMap.kManualMode));
      assertEquals(Optional.empty(), map.manualModeDefect(), "withManual cannot produce a defect");
    }

    /**
     * The manual bindings are registered first, so {@code MANUAL} is also the mode the robot boots
     * into — the mode that always works is live before anybody has touched the selector.
     */
    @Test
    void theRobotBootsIntoManual() {
      ControlMap<CommandGenericHID> map =
          ControlMap.withManual("Driver", m_driver, m -> {}).mode("AUTO_SCORE", m -> {});

      assertEquals(ControlMap.kManualMode, map.activeMode());
    }

    /** Even when MANUAL is declared last, it is still the mode that is live at boot. */
    @Test
    void manualWinsTheDefaultEvenWhenDeclaredLast() {
      ControlMap<CommandGenericHID> map =
          ControlMap.of("Driver", m_driver)
              .mode("AUTO_SCORE", m -> {})
              .mode(ControlMap.kManualMode, m -> {});

      assertEquals(ControlMap.kManualMode, map.activeMode());
    }

    @Test
    void manualModeIsSugarForModeManual() {
      ControlMap<CommandGenericHID> map = ControlMap.of("Driver", m_driver).manualMode(m -> {});

      assertEquals(List.of(ControlMap.kManualMode), map.modes());
      assertEquals(Optional.empty(), map.manualModeDefect());
    }
  }

  @Nested
  @DisplayName("of() without MANUAL — reported after the fact, by name, with the fix")
  final class MissingManual {

    @Test
    void aModalMapWithoutManualIsADefect() {
      ControlMap<CommandGenericHID> map =
          ControlMap.of("Operator", m_operator).mode("AUTO_SCORE", m -> {});

      assertTrue(map.manualModeDefect().isPresent(), "a modal map with no MANUAL is a defect");
    }

    @Test
    void theDefectNamesTheRoleTheDeclaredModesTheReasonAndBothFixes() {
      String defect =
          ControlMap.of("Operator", m_operator)
              .mode("AUTO_SCORE", m -> {})
              .manualModeDefect()
              .orElseThrow();

      assertTrue(defect.contains("\"Operator\""), defect);
      assertTrue(defect.contains("AUTO_SCORE"), defect);
      assertTrue(defect.contains(ControlMap.kManualMode), defect);
      assertTrue(
          defect.contains("occluded by a defender"),
          "the message must carry the reason, not just the rule. Was:\n" + defect);
      assertTrue(
          defect.contains(".mode(\"MANUAL\"") && defect.contains("ControlMap.withManual"),
          "both fixes must be named: add the mode, or switch to the shape that cannot forget. "
              + "Was:\n"
              + defect);
    }

    /**
     * A map with no modes at all is not a defect. It is a plain, non-modal control map, which is
     * what most teams start with, and flagging it would make the check noise.
     */
    @Test
    void aModelessMapIsNotADefect() {
      ControlMap<CommandGenericHID> map = ControlMap.of("Driver", m_driver);

      assertTrue(map.modes().isEmpty());
      assertEquals(Optional.empty(), map.manualModeDefect());
      assertEquals("", map.activeMode(), "no modes means no active mode");
    }

    /** With no MANUAL declared, the first declared mode is the fallback. */
    @Test
    void withoutManualTheFirstDeclaredModeIsTheDefault() {
      ControlMap<CommandGenericHID> map =
          ControlMap.of("Operator", m_operator).mode("AUTO_SCORE", m -> {}).mode("CLIMB", m -> {});

      assertEquals("AUTO_SCORE", map.activeMode());
    }
  }

  @Nested
  @DisplayName("mode declaration")
  final class Modes {

    /** Splitting a long MANUAL block across two call sites is not a mistake. */
    @Test
    void declaringTheSameModeTwiceAddsToItRatherThanDuplicatingIt() {
      ControlMap<CommandGenericHID> map =
          ControlMap.of("Driver", m_driver)
              .mode(ControlMap.kManualMode, m -> {})
              .mode(ControlMap.kManualMode, m -> {});

      assertEquals(List.of(ControlMap.kManualMode), map.modes());
    }

    /**
     * Modes do not nest: a binding gated on two modes at once can never fire, which is a dead button
     * nobody notices until a match.
     */
    @Test
    void modesDoNotNest() {
      IllegalArgumentException e =
          assertThrows(
              IllegalArgumentException.class,
              () ->
                  ControlMap.of("Driver", m_driver)
                      .mode("OUTER", outer -> outer.mode("INNER", inner -> {})));

      assertTrue(e.getMessage().contains("Modes do not nest"), e.getMessage());
      assertTrue(e.getMessage().contains("dead button"), e.getMessage());
    }

    /**
     * A throwing bindings block must not leave every later binding silently gated on a mode the
     * author thought they had closed.
     */
    @Test
    void aThrowingBindingsBlockStillClosesTheMode() {
      ControlMap<CommandGenericHID> map = ControlMap.of("Driver", m_driver);

      assertThrows(
          IllegalStateException.class,
          () ->
              map.mode(
                  "OUTER",
                  m -> {
                    throw new IllegalStateException("boom");
                  }));

      // If the mode were still open, this second declaration would be rejected as nesting.
      map.mode(ControlMap.kManualMode, m -> {});
      assertEquals(List.of("OUTER", ControlMap.kManualMode), map.modes());
    }

    @Test
    void modeRejectsBlankNamesAndNullBindings() {
      ControlMap<CommandGenericHID> map = ControlMap.of("Driver", m_driver);

      assertThrows(IllegalArgumentException.class, () -> map.mode("  ", m -> {}));
      assertThrows(IllegalArgumentException.class, () -> map.mode(null, m -> {}));
      assertThrows(NullPointerException.class, () -> map.mode("AUTO", null));
    }

    @Test
    void modesIsAStableSnapshot() {
      ControlMap<CommandGenericHID> map =
          ControlMap.of("Driver", m_driver).mode("A", m -> {}).mode("B", m -> {});

      assertEquals(List.of("A", "B"), map.modes());
    }
  }

  @Nested
  @DisplayName("construction")
  final class Construction {

    @Test
    void ofRejectsABlankRoleAndSaysWhatTheRoleIsFor() {
      IllegalArgumentException e =
          assertThrows(IllegalArgumentException.class, () -> ControlMap.of("  ", m_driver));
      assertTrue(
          e.getMessage().contains("CONTROLS.md"),
          "the role is the section heading on the printed poster. Was:\n" + e.getMessage());
      assertThrows(IllegalArgumentException.class, () -> ControlMap.of(null, m_driver));
    }

    @Test
    void ofRejectsANullHid() {
      assertThrows(NullPointerException.class, () -> ControlMap.of("Driver", null));
    }

    @Test
    void aMapKnowsItsRoleAndPort() {
      ControlMap<CommandGenericHID> map = ControlMap.of("Driver", m_driver);

      assertEquals("Driver", map.role());
      assertEquals(0, map.port());
      assertEquals(m_driver, map.raw());
    }

    @Test
    void everyMapIsRegisteredForThePosterAndThePortCheck() {
      ControlMap.of("Driver", m_driver);
      ControlMap.of("Operator", m_operator);

      assertEquals(2, ControlMap.all().size());
      assertTrue(ControlMap.isPortRegistered(0));
      assertTrue(ControlMap.isPortRegistered(1));
      assertFalse(ControlMap.isPortRegistered(2));
    }

    @Test
    void resetForTestForgetsEveryMap() {
      ControlMap.of("Driver", m_driver);

      ControlMap.resetForTest();

      assertTrue(ControlMap.all().isEmpty());
      assertFalse(ControlMap.isPortRegistered(0));
      assertTrue(ControlMap.defects().isEmpty());
    }
  }

  @Nested
  @DisplayName("the generated poster")
  final class Poster {

    @Test
    void markdownCoversEveryRegisteredRole() {
      ControlMap.withManual("Driver", m_driver, m -> {});
      ControlMap.withManual("Operator", m_operator, m -> {});

      String markdown = ControlMap.markdown();

      assertTrue(markdown.contains("Driver"), markdown);
      assertTrue(markdown.contains("Operator"), markdown);
    }

    /** The poster is where a missing MANUAL becomes visible to someone who is not reading Java. */
    @Test
    void markdownSurfacesAMissingManualMode() {
      ControlMap.of("Operator", m_operator).mode("AUTO_SCORE", m -> {});

      String markdown = ControlMap.markdown();

      assertTrue(
          markdown.contains(ControlMap.kManualMode),
          "the poster must show the defect, not hide it. Was:\n" + markdown);
    }
  }
}

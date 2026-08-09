package org.pumpkinlib.core.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.health.builtin.BatteryMonitor;
import org.pumpkinlib.core.health.builtin.BrownoutMonitor;
import org.pumpkinlib.core.health.builtin.CanBusMonitor;
import org.pumpkinlib.core.health.builtin.DeployMonitor;
import org.pumpkinlib.core.health.builtin.DsMonitor;
import org.pumpkinlib.core.health.builtin.LoopTimeMonitor;
import org.pumpkinlib.core.health.builtin.RailMonitor;

/**
 * There are EXACTLY SEVEN built-in health monitor types.
 *
 * <p><strong>Why this test exists at all.</strong> The count is quoted in four separate documents
 * and it drifted during design — the sentence "the seven built-in monitors" outlived at least one
 * revision in which there were not seven. A number repeated in four places and enforced in none is
 * a number that is already wrong somewhere; this test is the one place it is true by construction.
 * The failure message is deliberately explicit about the four other places to edit, because the
 * next person to add a monitor will hit this test and needs to know that changing the constant is
 * only the first of five edits.
 *
 * <p>The list is also pinned by identity, not just by size: a rename or a substitution that kept the
 * count at seven would otherwise slip through, and that is the exact shape of the drift this
 * catches.
 *
 * <p>No HAL. The monitors are named as class literals and never constructed — constructing one
 * would reach {@code Platform} and the HAL. What is under test is the roster, not the readings.
 */
final class BuiltinMonitorCountTest {

  /** The roster, spelled out here so a diff of this file is a diff of the promise. */
  private static final List<Class<?>> kExpectedTypes =
      List.of(
          CanBusMonitor.class,
          BatteryMonitor.class,
          RailMonitor.class,
          BrownoutMonitor.class,
          DeployMonitor.class,
          LoopTimeMonitor.class,
          DsMonitor.class);

  private static final String kWhereElseToEdit =
      "\n\nIf you meant to change the roster, the count is quoted in four documents besides this "
          + "test — DESIGN.md, ROADMAP.md, design/06-platform-compday.md and the HealthMonitor "
          + "javadoc. Update all of them in the same commit, or the next reader will trust the "
          + "wrong one.";

  @Test
  @DisplayName("there are exactly seven built-in monitor types")
  void thereAreExactlySevenBuiltinTypes() {
    assertEquals(
        7,
        HealthMonitor.builtinTypeCount(),
        "HealthMonitor.builtinTypeCount() is the number four documents quote." + kWhereElseToEdit);
  }

  @Test
  @DisplayName("builtinTypes() agrees with builtinTypeCount()")
  void theRosterAgreesWithTheCount() {
    assertEquals(
        HealthMonitor.builtinTypeCount(),
        HealthMonitor.builtinTypes().size(),
        "the advertised count and the actual roster must not be able to disagree — that is "
            + "precisely how the number drifted in the first place.");
  }

  @Test
  @DisplayName("the roster is exactly the seven named types, and each appears once")
  void theRosterIsExactlyTheSevenNamedTypes() {
    List<Class<?>> actual = HealthMonitor.builtinTypes();

    assertEquals(
        names(kExpectedTypes),
        names(actual),
        "a rename or a substitution that kept the count at seven is the drift this pins."
            + kWhereElseToEdit);
    assertEquals(
        actual.size(),
        actual.stream().distinct().count(),
        "a type listed twice would inflate the count without adding a check");
  }

  @Test
  @DisplayName("every built-in is a HealthSource")
  void everyBuiltinIsAHealthSource() {
    for (Class<?> type : HealthMonitor.builtinTypes()) {
      assertTrue(
          HealthSource.class.isAssignableFrom(type),
          type.getName()
              + " is on the built-in roster but does not implement HealthSource, so nothing would "
              + "ever poll it and its absence would be silent.");
    }
  }

  /**
   * Eight slices from seven types: {@code BrownoutMonitor} registers a second, every-loop latch
   * slice ({@code Health/BrownoutLatch}) because a brownout that begins and ends between two visits
   * of the rotation would otherwise be invisible. The two numbers are supposed to differ, and this
   * pins that they differ by exactly one.
   */
  @Test
  @DisplayName("seven types register eight slices, and the extra one is the brownout latch")
  void sevenTypesRegisterEightSlices() {
    assertEquals(8, HealthMonitor.builtinSliceCount());
    assertEquals(
        HealthMonitor.builtinTypeCount() + 1,
        HealthMonitor.builtinSliceCount(),
        "the one extra slice is BrownoutMonitor's every-loop latch ("
            + BrownoutMonitor.kLatchSliceName
            + "). If this moved, one of the two counts is now wrong.");
  }

  @Test
  @DisplayName("the seven types check eleven distinct conditions")
  void theBuiltinsCheckElevenConditions() {
    assertEquals(
        11,
        HealthMonitor.builtinConditionCount(),
        "the condition count is what the pit checklist and design/06 quote." + kWhereElseToEdit);
    assertTrue(
        HealthMonitor.builtinConditionCount() >= HealthMonitor.builtinTypeCount(),
        "every built-in type must contribute at least one condition, or it is a monitor that "
            + "monitors nothing");
  }

  private static List<String> names(List<Class<?>> types) {
    return types.stream().map(Class::getSimpleName).sorted().collect(Collectors.toList());
  }
}

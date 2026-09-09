package org.rootstock.superstructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rootstock.mechanism.Mechanism;

/**
 * A forbidden transition is <b>blocked</b>, the block is <b>named</b>, and the request is
 * <b>retained</b>.
 *
 * <p>Those three together are the whole contract. A driver who presses a button and sees nothing
 * happen presses it four more times; a driver who sees "no-score-until-homed: IDLE -&gt; L4 blocked
 * because 'the elevator has not homed yet; press Start to home'" walks over and presses Start. The
 * string asserted in {@link Named} below is byte-for-byte the string {@code
 * Superstructure.blockedReason()} publishes — {@code Superstructure} calls {@code
 * lock.describe(active, requested)} and stores the result verbatim — so pinning it here pins what
 * the dashboard shows.
 *
 * <p><strong>Native-free.</strong> {@link Interlock} is a record over two predicates and a boolean
 * supplier; nothing in this file constructs a {@code Mechanism} or a {@code Superstructure}, both of
 * which build {@code RootstockAlert}s and therefore need WPILib's JNI natives. The end-to-end wiring
 * — that a blocked request actually holds the superstructure and completes by itself when the
 * interlock releases — is asserted in {@code InterlockSuperstructureHalTest}.
 */
final class InterlockTest {

  /**
   * The design's own state machine, reduced to the four states the interlock cases need.
   *
   * <p>It implements {@link SuperState} with empty goal maps so the same enum can be handed to a
   * real {@code Superstructure} in the HAL tests without a second declaration drifting from this
   * one.
   */
  enum State implements SuperState {
    IDLE,
    INTAKE,
    L4,
    CLIMB;

    @Override
    public Map<Mechanism, AxisGoal> goals() {
      return Map.of();
    }
  }

  @Nested
  @DisplayName("applies — which transitions the rule has an opinion about")
  final class Applies {

    /** Both predicates must match. A rule about entering CLIMB says nothing about entering L4. */
    @Test
    void bothEndsOfTheTransitionMustMatch() {
      Interlock<State> climbLockout =
          new Interlock<>(
              "no-climb-with-piece",
              from -> true,
              to -> to == State.CLIMB,
              () -> false,
              "the claw is holding a game piece; eject before climbing");

      assertTrue(climbLockout.applies(State.IDLE, State.CLIMB));
      assertTrue(climbLockout.applies(State.L4, State.CLIMB));
      assertFalse(climbLockout.applies(State.IDLE, State.L4));
      assertFalse(climbLockout.applies(State.CLIMB, State.IDLE), "leaving CLIMB is not entering it");
    }

    /** A rule scoped to a source state ignores transitions that start anywhere else. */
    @Test
    void aSourceScopedRuleIgnoresOtherSources() {
      Interlock<State> onlyFromIdle =
          new Interlock<>("only-from-idle", from -> from == State.IDLE, to -> true, () -> false, "");
      assertTrue(onlyFromIdle.applies(State.IDLE, State.L4));
      assertFalse(onlyFromIdle.applies(State.INTAKE, State.L4));
    }
  }

  @Nested
  @DisplayName("blocks — the answer that stops the move")
  final class Blocks {

    /**
     * The design's flagship interlock, exactly as {@code RobotContainer} declares it: nothing but
     * IDLE is reachable until both axes have homed.
     */
    @Test
    void anUnsatisfiedRuleBlocksEveryTransitionItAppliesTo() {
      AtomicBoolean homed = new AtomicBoolean(false);
      Interlock<State> lock =
          new Interlock<>(
              "no-score-until-homed",
              from -> true,
              to -> to != State.IDLE,
              homed::get,
              "the elevator has not homed yet; press Start to home");

      assertTrue(lock.blocks(State.IDLE, State.L4), "not homed: L4 is refused");
      assertTrue(lock.blocks(State.IDLE, State.INTAKE));
      assertFalse(lock.blocks(State.L4, State.IDLE), "IDLE is always reachable — the escape hatch");

      homed.set(true);
      assertFalse(lock.blocks(State.IDLE, State.L4), "homing releases it with no code change");
    }

    /** A rule that applies but is satisfied blocks nothing, and one that does not apply never does. */
    @Test
    void applyingAndBlockingAreDifferentQuestions() {
      Interlock<State> lock =
          new Interlock<>("permitted", from -> true, to -> to == State.L4, () -> true, "why");
      assertTrue(lock.applies(State.IDLE, State.L4));
      assertFalse(lock.blocks(State.IDLE, State.L4));
    }

    /**
     * Fail closed. A condition that throws when it is evaluated — a null-dereferencing lambda over a
     * sensor that has not been wired yet — counts as NOT permitted.
     *
     * <p>The alternative is moving a superstructure on the strength of a check that just crashed,
     * which is how an arm goes through a chassis crossbar.
     */
    @Test
    void aConditionThatThrowsIsTreatedAsBlocking() {
      Interlock<State> broken =
          new Interlock<>(
              "broken-sensor",
              from -> true,
              to -> true,
              () -> {
                throw new IllegalStateException("sensor not wired");
              },
              "the sensor is not wired");

      assertFalse(broken.isPermitted(), "unevaluable means not permitted");
      assertTrue(broken.blocks(State.IDLE, State.L4));
    }

    /** A predicate that throws cannot be said to match, and must not take the loop down with it. */
    @Test
    void aPredicateThatThrowsMatchesNothingAndDoesNotEscape() {
      Interlock<State> broken =
          new Interlock<>(
              "broken-predicate",
              from -> {
                throw new IllegalStateException("boom");
              },
              to -> true,
              () -> false,
              "");
      assertFalse(broken.applies(State.IDLE, State.L4));
      assertFalse(broken.blocks(State.IDLE, State.L4));
    }
  }

  @Nested
  @DisplayName("named — the sentence the driver reads")
  final class Named {

    /**
     * The exact string {@code Superstructure.blockedReason()} publishes. {@code Superstructure}
     * stores {@code lock.describe(active, requested)} verbatim into {@code m_blockedText}, so this
     * assertion is the dashboard contract and not a formatting preference.
     */
    @Test
    void theBlockedSentenceNamesTheRuleTheTransitionAndTheFix() {
      Interlock<State> lock =
          new Interlock<>(
              "no-score-until-homed",
              from -> true,
              to -> to != State.IDLE,
              () -> false,
              "the elevator has not homed yet; press Start to home");

      assertEquals(
          "no-score-until-homed: IDLE -> L4 blocked because "
              + "'the elevator has not homed yet; press Start to home'",
          lock.describe(State.IDLE, State.L4));
    }

    /** The standalone form, for the boot dump and the report, states the live verdict. */
    @Test
    void theReportLineStatesWhetherItIsCurrentlyBlocking() {
      AtomicBoolean ok = new AtomicBoolean(false);
      Interlock<State> lock =
          new Interlock<>("no-climb-with-piece", from -> true, to -> true, ok::get, "eject first");

      assertEquals("no-climb-with-piece (currently BLOCKING): eject first", lock.describe());
      ok.set(true);
      assertEquals("no-climb-with-piece (currently PERMITTED): eject first", lock.describe());
    }

    /** A null state still renders, because a report line that throws is a report nobody sees. */
    @Test
    void aNullStateRendersRatherThanThrowing() {
      Interlock<State> lock = new Interlock<>("r", from -> true, to -> true, () -> false, "why");
      assertEquals("r: ? -> L4 blocked because 'why'", lock.describe(null, State.L4));
      assertEquals("r: IDLE -> ? blocked because 'why'", lock.describe(State.IDLE, null));
    }
  }

  @Nested
  @DisplayName("declared in a static final list, so nothing may throw there")
  final class NeverThrows {

    /**
     * Interlocks are declared next to the configs they guard, in {@code static final} fields. A
     * throw from that context is an {@code ExceptionInInitializerError} with no message, so every
     * null is normalised instead.
     */
    @Test
    void everyNullIsNormalised() {
      Interlock<State> bare = new Interlock<>(null, null, null, null, null);

      assertEquals(Interlock.kUnnamed, bare.name());
      assertTrue(bare.applies(State.IDLE, State.L4), "a null predicate matches everything");
      assertTrue(bare.isPermitted(), "a null condition is always permitted");
      assertFalse(bare.blocks(State.IDLE, State.L4));
      assertEquals("", bare.explanation());
    }

    /** A blank name is normalised too, so no report line is ever an empty string. */
    @Test
    void aBlankNameBecomesTheUnnamedPlaceholder() {
      assertEquals(
          Interlock.kUnnamed,
          new Interlock<State>("   ", null, null, null, "why").name());
      assertEquals("kept", new Interlock<State>("  kept  ", null, null, null, "why").name());
    }
  }
}

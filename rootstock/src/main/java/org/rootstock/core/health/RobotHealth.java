package org.rootstock.core.health;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.rootstock.core.alert.AlertRegistry;
import org.rootstock.core.alert.Severity;

/**
 * The one-line answer to "is the robot OK right now?" — and, separately and more importantly, to
 * "can this robot play a match?".
 *
 * <p>Those are different questions and conflating them is what makes a status light untrustworthy.
 * A robot with nine {@code PIT_ONLY} warnings and no blocking alerts <strong>is ready</strong>, the
 * LEDs go green, and the driver takes the field — because that is the honest answer, and an honest
 * answer is the only kind that stays trusted. {@link #isReady()} rolls up {@code BLOCKS_MATCH}
 * alerts exclusively; {@link #worst()} and {@link #hasWarning()} answer the other question.
 *
 * <p>All state is static. There is one robot.
 */
public final class RobotHealth {

  private static BooleanSupplier m_batterySagging = () -> false;

  private RobotHealth() {}

  /**
   * The worst severity across every fault reported by every registered {@link HealthSource} on its
   * most recent poll.
   *
   * <p>Faults, not alerts: this is the diagnostic rollup for the pit tab. Because sources are polled
   * round-robin, "most recent poll" for a given source is at most one sweep old.
   *
   * @return the worst severity, or empty when nothing anywhere is reporting a fault
   */
  public static Optional<Severity> worst() {
    Severity worst = null;
    for (HealthMonitor reg : HealthMonitor.all()) {
      for (Fault f : reg.faults()) {
        worst = worst == null ? f.severity() : Severity.max(worst, f.severity());
      }
    }
    return Optional.ofNullable(worst);
  }

  /**
   * Every fault currently reported, across every source, in slice order.
   *
   * @return an unmodifiable snapshot; expected-absent demotion has already been applied
   */
  public static List<Fault> active() {
    List<Fault> out = new ArrayList<>();
    for (HealthMonitor reg : HealthMonitor.all()) {
      out.addAll(reg.faults());
    }
    return Collections.unmodifiableList(out);
  }

  /**
   * THE match-readiness answer.
   *
   * <p>"Ready" means "this robot can play a match", not "nothing anywhere is imperfect". It rolls up
   * {@code BLOCKS_MATCH} alerts exclusively and delegates to {@link AlertRegistry#matchReady()},
   * which is the canonical implementation.
   *
   * @return true when no {@code BLOCKS_MATCH} alert is active
   */
  public static boolean isReady() {
    return AlertRegistry.matchReady();
  }

  /**
   * Deprecated spelling of {@link #isReady()}, kept for one season.
   *
   * <p>{@link AlertRegistry#matchReady()} is the canonical implementation and is <em>not</em>
   * deprecated — this is the duplicate, not that one.
   *
   * @return {@link #isReady()}
   * @deprecated use {@link #isReady()}
   */
  @Deprecated
  public static boolean matchReady() {
    return isReady();
  }

  /**
   * Whether any registered source is reporting an {@link Severity#ERROR} fault.
   *
   * <p>Wired to the red LED state. Note this is about faults, not about whether you may play — see
   * {@link #isReady()}.
   *
   * @return true if any active fault is an ERROR
   */
  public static boolean hasError() {
    return hasAtLeast(Severity.ERROR);
  }

  /**
   * Whether any registered source is reporting a {@link Severity#WARNING} fault or worse.
   *
   * @return true if any active fault is a WARNING or an ERROR
   */
  public static boolean hasWarning() {
    return hasAtLeast(Severity.WARNING);
  }

  /**
   * Whether battery voltage is currently sagging past the configured limit.
   *
   * <p>Read by {@code CompressorPolicy.standard()}, which stops charging while the battery is
   * already struggling — a compressor drawing 10 A during a defensive push is how a brownout becomes
   * a lost match.
   *
   * <p>The value comes from {@code BatteryMonitor}, which installs itself through {@link
   * #setBatterySaggingSource(BooleanSupplier)} when it registers. With no battery monitor installed
   * this is {@code false}, which is the correct answer for "we are not measuring".
   *
   * @return true while the sag condition holds
   */
  public static boolean batterySagging() {
    return m_batterySagging.getAsBoolean();
  }

  /**
   * Install the source of {@link #batterySagging()}.
   *
   * <p>Called by {@code BatteryMonitor.register()}. Exposed rather than reaching into {@code
   * core.health.builtin} from here so that a team with a custom battery model can supply their own,
   * and so that this class works with no built-ins installed at all.
   *
   * @param sagging the predicate; null resets to "not measuring"
   */
  public static void setBatterySaggingSource(BooleanSupplier sagging) {
    m_batterySagging = sagging == null ? () -> false : sagging;
  }

  /**
   * A multi-line health summary for the pit printout and the boot log.
   *
   * @return readiness, the worst severity, and every active fault
   */
  public static String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("RobotHealth: ")
        .append(isReady() ? "READY" : "NOT READY (blocking alert active)")
        .append(", worst = ")
        .append(worst().map(Enum::name).orElse("none"))
        .append('\n');
    for (Fault f : active()) {
      sb.append("  ").append(f.describe()).append('\n');
    }
    return sb.toString();
  }

  /** Reset the installed battery-sag source. Tests only. */
  public static void resetForTest() {
    m_batterySagging = () -> false;
  }

  private static boolean hasAtLeast(Severity floor) {
    for (HealthMonitor reg : HealthMonitor.all()) {
      for (Fault f : reg.faults()) {
        if (f.severity().atLeast(floor)) {
          return true;
        }
      }
    }
    return false;
  }
}

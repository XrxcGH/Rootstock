package org.pumpkinlib.core.selftest;

import edu.wpi.first.networktables.BooleanPublisher;
import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringPublisher;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.match.MatchContext;

/**
 * The pit button.
 *
 * <p>Plug in, enable Test, press one button, read a green/red list. Ninety seconds, repeatable, no
 * tribal knowledge — every mechanism moves, every sensor reports, every CAN device answers, every
 * controller is in the right slot, and each one says PASS or FAIL with the number it actually
 * measured.
 *
 * <pre>{@code
 * // in the robot's binding code:
 * m_operator.start().onTrue(SelfTest.runAll());
 * }</pre>
 *
 * <p><strong>Safety.</strong> {@link #runAll()} refuses to do anything unless {@code
 * MatchContext.isDiagnostics()}, or the robot is disabled and not FMS-attached — and when it
 * refuses, it says so, loudly, with the reason. A button that silently does nothing is worse than no
 * button. Beyond the gate, every step is bounded by a timeout and the routine aborts if a blocking
 * alert activates mid-run.
 *
 * <p>All state is static. There is one robot.
 */
public final class SelfTest {

  /** NetworkTables root for results. */
  public static final String kRoot = "/Pumpkin/SelfTest/";

  /** NetworkTables topic carrying the whole-robot summary block. */
  public static final String kSummaryTopic = kRoot + "Summary";

  /** Alert group for the self-test's own messages. */
  public static final String kGroup = "SelfTest";

  private static final Map<String, Supplier<SelfTestRoutine>> m_routines = new LinkedHashMap<>();
  private static final Map<String, SelfTestResult> m_lastResults = new LinkedHashMap<>();
  private static final Map<String, Publishers> m_publishers = new LinkedHashMap<>();

  private static PumpkinAlert m_refusalAlert;
  private static boolean m_refused;
  private static StringPublisher m_summaryPublisher;

  private SelfTest() {}

  /**
   * Register a {@link SelfTestable} target.
   *
   * <p>Not the advertised entry point (D27): {@code PumpkinRegistry.addAll(...)} routes anything
   * {@code instanceof SelfTestable} here, and {@code .excludeFrom(Registry.SELFTEST)} is the opt-out.
   * Declared {@code public} rather than package-private as sketched because that router lives in a
   * different package; treat it as internal.
   *
   * @param target the target; its {@code selfTestRoutine()} is called fresh for every run
   * @throws IllegalArgumentException if {@code target} is null or its name is blank
   */
  public static void register(SelfTestable target) {
    if (target == null) {
      throw new IllegalArgumentException("SelfTest.register: target is null.");
    }
    String name = target.selfTestName();
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "SelfTest.register: "
              + target.getClass().getName()
              + ".selfTestName() is blank. It is the result key and the NT subtable.");
    }
    m_routines.put(name, target::selfTestRoutine);
  }

  /**
   * Register a named routine that has no owning object to route.
   *
   * <p>This is the one direct registration that survived D27, deliberately: a bare routine has no
   * object for {@code PumpkinRegistry} to inspect, so there is nothing to collapse. It is also the
   * form the documentation example uses.
   *
   * <pre>{@code
   * SelfTest.register("Arm", () -> SelfTestRoutine.of("Arm")
   *     .step("extend", arm.toAngle(Degrees.of(90)))
   *         .expect(arm::angle, Degrees.of(90), Degrees.of(2)).withTimeout(Seconds.of(2))
   *     .build());
   * }</pre>
   *
   * @param name the result key and NT subtable
   * @param routine builds the routine; called fresh for every run, never cached
   * @throws IllegalArgumentException if {@code name} is blank or {@code routine} is null
   */
  public static void register(String name, Supplier<SelfTestRoutine> routine) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("SelfTest.register: name is blank.");
    }
    if (routine == null) {
      throw new IllegalArgumentException("SelfTest.register(\"" + name + "\"): routine is null.");
    }
    m_routines.put(name, routine);
  }

  /**
   * Sequence every registration: run each routine in turn, catch and time each, publish each.
   *
   * <p>Bind this to a pit button. The returned command is fresh each call and rebuilds every routine
   * from its supplier, so consecutive presses measure the robot as it is now.
   *
   * @return the command; if nothing is registered it reports that fact rather than passing silently
   */
  public static Command runAll() {
    if (m_routines.isEmpty()) {
      return Commands.runOnce(
              () ->
                  publish(
                      new SelfTestResult(
                          "runAll",
                          false,
                          0.0,
                          List.of(),
                          "no self-tests are registered. Implement SelfTestable on a mechanism, or "
                              + "call SelfTest.register(name, routineSupplier) - a green screen "
                              + "that tested nothing is the most dangerous screen in the pit.")))
          .ignoringDisable(true)
          .withName("SelfTest/runAll(empty)");
    }
    List<Command> runners = new ArrayList<>();
    for (String name : m_routines.keySet()) {
      runners.add(run(name));
    }
    return Commands.sequence(runners.toArray(new Command[0])).withName("SelfTest/runAll");
  }

  /**
   * Run one registered routine by name.
   *
   * @param name the registration name
   * @return the command; if nothing is registered under that name it reports that, listing what is
   */
  public static Command run(String name) {
    Supplier<SelfTestRoutine> supplier = m_routines.get(name);
    if (supplier == null) {
      return Commands.runOnce(
              () ->
                  publish(
                      new SelfTestResult(
                          name,
                          false,
                          0.0,
                          List.of(),
                          "no self-test is registered under \""
                              + name
                              + "\". Registered: "
                              + (m_routines.isEmpty() ? "(none)" : String.join(", ", names())))))
          .ignoringDisable(true)
          .withName("SelfTest/" + name + "(missing)");
    }
    return new SelfTestRunner(supplier.get());
  }

  /**
   * The most recent result for every routine that has run.
   *
   * @return an unmodifiable list in registration order
   */
  public static List<SelfTestResult> lastResults() {
    return Collections.unmodifiableList(new ArrayList<>(m_lastResults.values()));
  }

  /**
   * The most recent result for one routine.
   *
   * @param name the registration name
   * @return the result, or empty if it has not run since boot
   */
  public static Optional<SelfTestResult> lastResult(String name) {
    return Optional.ofNullable(m_lastResults.get(name));
  }

  /**
   * The registered routine names, in registration order.
   *
   * @return an unmodifiable list
   */
  public static List<String> names() {
    return Collections.unmodifiableList(new ArrayList<>(m_routines.keySet()));
  }

  /**
   * Whether every routine that has run so far passed.
   *
   * @return false if any recorded result failed, or if nothing has run
   */
  public static boolean allPassed() {
    if (m_lastResults.isEmpty()) {
      return false;
    }
    return m_lastResults.values().stream().allMatch(SelfTestResult::passed);
  }

  /**
   * The human-readable block for the Elastic text widget and the pit printout.
   *
   * @return a header line plus every result, or an explanation of why there is nothing to show
   */
  public static String summary() {
    if (m_lastResults.isEmpty()) {
      return "SelfTest: nothing has run yet ("
          + m_routines.size()
          + " routine(s) registered). Enable Test mode and press the self-test button.\n";
    }
    int passed = (int) m_lastResults.values().stream().filter(SelfTestResult::passed).count();
    StringBuilder sb = new StringBuilder();
    sb.append("SelfTest: ")
        .append(passed)
        .append('/')
        .append(m_lastResults.size())
        .append(" routines passed\n");
    for (SelfTestResult r : m_lastResults.values()) {
      sb.append(r.describe());
    }
    return sb.toString();
  }

  /**
   * A description of the registry, for the boot log.
   *
   * @return the registered names and the readiness of the safety gate
   */
  public static String describe() {
    return "SelfTest: "
        + m_routines.size()
        + " registered ("
        + (m_routines.isEmpty() ? "none" : String.join(", ", names()))
        + "), gate is "
        + (gateOpen() ? "open" : "shut - " + gateRefusalReason())
        + "\n";
  }

  /** Drop every registration and every recorded result. Tests only. */
  public static void resetForTest() {
    m_routines.clear();
    m_lastResults.clear();
    for (Publishers p : m_publishers.values()) {
      p.close();
    }
    m_publishers.clear();
    if (m_summaryPublisher != null) {
      m_summaryPublisher.close();
      m_summaryPublisher = null;
    }
    m_refusalAlert = null;
    m_refused = false;
  }

  // ---------------------------------------------------------------- the safety gate

  /**
   * Whether a self-test may run right now.
   *
   * <p>Test mode, or disabled and not on a field. The one thing this must never do is run a
   * mechanism while the robot is enabled on an FMS.
   *
   * @return true when a routine may be scheduled
   */
  static boolean gateOpen() {
    return MatchContext.isDiagnostics()
        || (MatchContext.isDisabled() && !MatchContext.isFMSAttached());
  }

  /**
   * Why the gate is shut, in words a pit operator can act on.
   *
   * @return the reason, naming the current phase and the fix
   */
  static String gateRefusalReason() {
    return "the robot is "
        + MatchContext.phase().label()
        + (MatchContext.isFMSAttached() ? " and attached to the FMS" : "")
        + ". A self-test moves mechanisms, so it will not start unless the robot is in Test mode, "
        + "or disabled and off the field. Switch the Driver Station to Test and enable.";
  }

  /** Raise the refusal alert. Self-clearing: it stands down as soon as the gate opens. */
  static void raiseRefusal(String routineName) {
    m_refused = true;
    if (m_refusalAlert == null) {
      m_refusalAlert =
          Alerts.when(
              kGroup,
              "self-test refused to run",
              org.pumpkinlib.core.alert.Severity.ERROR,
              MatchImpact.BLOCKS_MATCH,
              () -> m_refused && !gateOpen());
    }
    m_refusalAlert.text(
        "self-test \"" + routineName + "\" refused to run: " + gateRefusalReason());
  }

  /** Note that a routine started cleanly, so the refusal alert stops asserting. */
  static void clearRefusal() {
    m_refused = false;
  }

  // ---------------------------------------------------------------- publishing

  /**
   * Record and publish one result. Called by the runner; not part of the public surface.
   *
   * @param result the result
   */
  static void publish(SelfTestResult result) {
    m_lastResults.put(result.name(), result);
    try {
      Publishers p = m_publishers.computeIfAbsent(result.name(), Publishers::new);
      p.status.set(result.status());
      p.passed.set(result.passed());
      p.ran.set(true);
      p.detail.set(result.detail());
      p.durationSec.set(result.durationSec());

      if (m_summaryPublisher == null) {
        m_summaryPublisher = NetworkTableInstance.getDefault().getStringTopic(kSummaryTopic).publish();
      }
      m_summaryPublisher.set(summary());
    } catch (RuntimeException e) {
      // NetworkTables is unavailable (a pure-JVM unit test, say). The result is still recorded and
      // still readable through lastResults() and summary().
      m_publishers.remove(result.name());
      m_summaryPublisher = null;
    }
  }

  /** The five topics one routine publishes. */
  private static final class Publishers {
    private final StringPublisher status;
    private final BooleanPublisher passed;
    private final BooleanPublisher ran;
    private final StringPublisher detail;
    private final DoublePublisher durationSec;

    Publishers(String name) {
      NetworkTableInstance nt = NetworkTableInstance.getDefault();
      String root = kRoot + name + "/";
      status = nt.getStringTopic(root + "Status").publish();
      passed = nt.getBooleanTopic(root + "Passed").publish();
      ran = nt.getBooleanTopic(root + "Ran").publish();
      detail = nt.getStringTopic(root + "Detail").publish();
      durationSec = nt.getDoubleTopic(root + "DurationSec").publish();
    }

    void close() {
      status.close();
      passed.close();
      ran.close();
      detail.close();
      durationSec.close();
    }
  }
}

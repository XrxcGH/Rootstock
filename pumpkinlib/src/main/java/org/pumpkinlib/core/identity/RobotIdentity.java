package org.pumpkinlib.core.identity;

import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.pumpkinlib.core.compat.Platform;
import org.pumpkinlib.core.config.DiagnosticsGate;
import org.pumpkinlib.core.config.PersistentStore;

/**
 * Which robot am I, and how do I know?
 *
 * <p>Declare the strategy table once, in {@code frc/robot/robots/RobotIds.java}, before any config
 * is read:
 *
 * <pre>{@code
 * RobotIdentity.configure()
 *     .byPersistentFile(true)
 *     .byComment("practice", RobotId.PRACTICE)
 *     .bySerial("031b7f8e", RobotId.COMP)
 *     .simIs(RobotId.SIM)
 *     .fallback(RobotId.COMP)   // fail SAFE: a mystery robot behaves like the comp bot
 *     .done();
 * }</pre>
 *
 * <p>Then write constants once and describe only the deltas:
 *
 * <pre>{@code
 * public static final ElevatorConfig kElevator = RobotIdentity.overlay(
 *     ElevatorConfig.base().withGearing(12.0).withMaxHeight(Inches.of(58)),
 *     Map.of(RobotId.PRACTICE, c -> c.withMaxHeight(Inches.of(54)),
 *            RobotId.PROTO,    c -> c.withGearing(9.0)));
 * }</pre>
 *
 * <p><b>Identity has to live on the robot.</b> A {@code robot-id.txt} in the deploy directory does
 * not work, because the deploy directory is deployed <i>from the laptop</i>: every robot receives
 * the same file. The five strategies, their order, and why each one exists are documented on
 * {@link IdentityResolver}; the resolution itself lives there so it can be unit tested without an
 * HAL. This class is the static facade robot code talks to, plus the two things a facade has to own:
 * gathering the platform reads and remembering the answer.
 *
 * <p><b>This package contains no {@code edu.wpi.first.wpilibj} import</b> — every platform fact
 * arrives through {@code org.pumpkinlib.core.compat.Platform}, which is the one package allowed to
 * touch year-volatile WPILib API (ArchUnit rule 2). When {@code getComments()} disappears on
 * SystemCore, strategy 2 becomes {@code Optional.empty()} in one file and the chain degrades with a
 * named alert rather than failing to compile.
 *
 * @see IdentityResolver
 * @see Overlay
 */
public final class RobotIdentity {

  /** Path of the on-robot identity file, relative to {@code Platform.persistentDir()/pumpkin}. */
  public static final String kIdentityFileName = "robot-id";

  private static volatile IdentityResolver s_resolver;
  private static volatile IdentityResolver.Resolution s_resolution;

  private RobotIdentity() {}

  /**
   * Starts the declarative registration. Call exactly once, before any config is read.
   *
   * @return a fresh builder; nothing takes effect until {@link Builder#done()}
   */
  public static Builder configure() {
    return new Builder();
  }

  /**
   * The resolved identity of this robot.
   *
   * @return the resolved {@link RobotId}
   * @throws IllegalStateException if {@link #configure()} was never completed with
   *     {@link Builder#done()} — returning a plausible default here would let a robot silently run
   *     an unknown robot's gearing, which is the failure this whole mechanism exists to prevent
   */
  public static RobotId current() {
    IdentityResolver.Resolution resolution = s_resolution;
    if (resolution == null) {
      throw new IllegalStateException(
          "RobotIdentity.current() was called before RobotIdentity.configure()...done().\n"
              + "  PumpkinLib does not guess which robot it is running on.\n"
              + "  Fix: call RobotIds.register() (or the equivalent RobotIdentity.configure()"
              + " chain)\n"
              + "       from your Robot constructor, BEFORE any config constant is read. The"
              + " template\n"
              + "       ships this wired in frc/robot/robots/RobotIds.java.");
    }
    return resolution.id();
  }

  /**
   * The resolved identity, if {@link #configure()} has completed.
   *
   * <p>The non-throwing companion to {@link #current()}, for code that runs before or independently
   * of identity configuration — {@code DeployInfo.summary()} is the motivating caller, because a
   * pit-display line must never be the thing that throws.
   *
   * @return the resolved identity, or empty if identity was never configured
   */
  public static Optional<RobotId> currentIfResolved() {
    IdentityResolver.Resolution resolution = s_resolution;
    return resolution == null ? Optional.empty() : Optional.of(resolution.id());
  }

  /**
   * Whether {@link #configure()} has completed.
   *
   * @return true once {@link Builder#done()} has run
   */
  public static boolean isConfigured() {
    return s_resolution != null;
  }

  /**
   * How the identity was resolved.
   *
   * <p>Published to {@code /Pumpkin/Meta/RobotIdSource} and shown in the pit, because "COMP" alone
   * does not tell a student whether to go edit the comments field or the serial table.
   *
   * @return a one-line description such as {@code comments: "Practice Bot" contains "practice"}, or
   *     {@code "not configured"} if {@link #configure()} never completed
   */
  public static String source() {
    IdentityResolver.Resolution resolution = s_resolution;
    return resolution == null ? "not configured" : resolution.source();
  }

  /**
   * The full resolution record, including every strategy that was tried and what it saw.
   *
   * <p>This is what the {@code ERROR} / {@code BLOCKS_MATCH} fallback alert is built from. The alert
   * itself is raised by {@code org.pumpkinlib.core.alert}, which owns alert severity and match
   * impact; identity's job is to make the facts available without depending on the alert package.
   *
   * @return the resolution, or empty if identity was never configured
   */
  public static Optional<IdentityResolver.Resolution> resolution() {
    return Optional.ofNullable(s_resolution);
  }

  /**
   * Whether the identity came from the declared fallback because nothing matched.
   *
   * <p>True here means the robot may be running the wrong gearing, the wrong soft limits and the
   * wrong offsets. It is one of the five conditions the alert budget reserves for
   * {@code ERROR} / {@code BLOCKS_MATCH}.
   *
   * @return true if every strategy failed and the fallback was used
   */
  public static boolean usedFallback() {
    IdentityResolver.Resolution resolution = s_resolution;
    return resolution != null && resolution.usedFallback();
  }

  /**
   * The declared strategy table, for {@code pumpkin doctor} and the boot dump.
   *
   * @return the resolver, or empty if identity was never configured
   */
  public static Optional<IdentityResolver> resolver() {
    return Optional.ofNullable(s_resolver);
  }

  /**
   * Picks a value per robot, requiring every robot to be covered.
   *
   * <p>Use this when the value genuinely has no sensible default — a camera transform, a swerve
   * module offset table. Use {@link Overlay} instead when there is a base value and only deltas.
   *
   * @param byRobot the value for each robot
   * @param <T> the value type
   * @return the value registered for the resolved identity
   * @throws IllegalArgumentException if this robot has no entry, naming the robot, the keys that
   *     were registered, and the caller's source location
   * @throws IllegalStateException if identity was never configured
   */
  public static <T> T pick(Map<RobotId, T> byRobot) {
    if (byRobot == null || byRobot.isEmpty()) {
      throw new IllegalArgumentException(
          "RobotIdentity.pick(byRobot): the map was "
              + (byRobot == null ? "null" : "empty")
              + ". Register one value per robot, for example Map.of(RobotId.COMP, a,"
              + " RobotId.PRACTICE, b).");
    }
    RobotId id = current();
    T value = byRobot.get(id);
    if (value == null) {
      throw new IllegalArgumentException(
          "RobotIdentity.pick(...) has no value for "
              + id
              + ", the identity this robot resolved to ("
              + source()
              + ").\n"
              + "  Registered: "
              + byRobot.keySet()
              + "\n  Called from: "
              + callerLocation()
              + "\n  Fix: add a "
              + id
              + " entry to that map, or use RobotIdentity.overlay(base, deltas) if there is a"
              + " sensible shared default.");
    }
    return value;
  }

  /**
   * Picks a value per robot, falling back to a shared default.
   *
   * <p>The non-throwing companion to {@link #pick(Map)}, and the right choice for a value where a
   * missing entry is genuinely fine.
   *
   * @param byRobot the value for each robot; may be missing entries
   * @param defaultValue the value used when this robot has no entry, or when identity was never
   *     configured
   * @param <T> the value type
   * @return the registered value, or {@code defaultValue}
   */
  public static <T> T pickOrDefault(Map<RobotId, T> byRobot, T defaultValue) {
    Optional<RobotId> id = currentIfResolved();
    if (id.isEmpty() || byRobot == null) {
      return defaultValue;
    }
    return byRobot.getOrDefault(id.get(), defaultValue);
  }

  /**
   * Applies per-robot overrides on top of a base value.
   *
   * <p>Equivalent to building an {@link Overlay} with one {@code when(...)} layer per map entry and
   * resolving it; see {@link Overlay} for the documented precedence order. Map iteration order is
   * unspecified for {@code Map.of(...)}, so registering two overrides for the <i>same</i> robot is
   * not possible here by construction — use {@link Overlay} directly if you need layered rules.
   *
   * @param base the config every robot shares
   * @param overrides a copy function per robot; a robot with no entry gets {@code base}
   * @param <T> the config type
   * @return the overlaid value for this robot
   * @throws IllegalStateException if identity was never configured
   */
  public static <T> T overlay(T base, Map<RobotId, Function<T, T>> overrides) {
    return overlayOf(base, overrides).resolve();
  }

  /**
   * Builds the {@link Overlay} that {@link #overlay(Object, Map)} resolves.
   *
   * <p>Exposed because the overlay itself is the testable object: a unit test asserts
   * {@code overlayOf(base, deltas).resolveFor(RobotId.PRACTICE)} with no identity configured and no
   * HAL.
   *
   * @param base the config every robot shares
   * @param overrides a copy function per robot
   * @param <T> the config type
   * @return the overlay
   */
  public static <T> Overlay<T> overlayOf(T base, Map<RobotId, Function<T, T>> overrides) {
    Overlay<T> overlay = Overlay.of(base);
    if (overrides != null) {
      for (Map.Entry<RobotId, Function<T, T>> entry : overrides.entrySet()) {
        Function<T, T> fn = entry.getValue();
        if (fn == null) {
          throw new IllegalArgumentException(
              "RobotIdentity.overlay(base, overrides): the override for "
                  + entry.getKey()
                  + " was null. Pass a withX() copy chain, for example c -> c.withGearing(9.0).");
        }
        UnaryOperator<T> op = fn::apply;
        overlay = overlay.when(entry.getKey(), op);
      }
    }
    return overlay;
  }

  /**
   * Writes this robot's identity file, so it identifies itself from now on.
   *
   * <p>Writes {@code <persistentDir>/pumpkin/robot-id}. That is strategy 1 — the on-robot file that
   * survives a redeploy — and writing it from a dashboard button is the reason no student ever has
   * to SSH into a robot to set its identity.
   *
   * <p>Never throws: an unwritable filesystem returns false and the reason is available from
   * {@link PersistentStore#lastError()}. The new identity takes effect on the <b>next boot</b>; the
   * resolved value for this run is deliberately not mutated, because half the constants in the
   * running JVM were already resolved with the old one.
   *
   * @param id the identity to record on this robot
   * @return true if the file was written
   */
  public static boolean assign(RobotId id) {
    if (id == null) {
      throw new IllegalArgumentException("RobotIdentity.assign(id): id was null.");
    }
    return PersistentStore.persistent().writeText(kIdentityFileName, id.name() + System.lineSeparator());
  }

  /**
   * A pit button that writes this robot's identity file.
   *
   * <p>Runs while disabled (that is when you are in the pit) and is gated on
   * {@link DiagnosticsGate}, which {@code MatchContext} installs from
   * {@code DriverStation.isTest()}: rewriting a robot's identity during a match is never intended,
   * and the gate makes the button safe to leave bound all season.
   *
   * @param id the identity to record on this robot
   * @return a command that writes the identity file once, or does nothing (and says so) when the
   *     robot is not in diagnostics mode
   */
  public static Command assignCommand(RobotId id) {
    if (id == null) {
      throw new IllegalArgumentException("RobotIdentity.assignCommand(id): id was null.");
    }
    return Commands.runOnce(
            () -> {
              if (!DiagnosticsGate.allowed()) {
                System.out.println(
                    "[PumpkinLib] RobotIdentity.assignCommand("
                        + id
                        + ") ignored: the robot is not in diagnostics (Test) mode. "
                        + DiagnosticsGate.describe());
                return;
              }
              if (assign(id)) {
                System.out.println(
                    "[PumpkinLib] Wrote robot identity "
                        + id
                        + " to "
                        + PersistentStore.persistent().resolve(kIdentityFileName)
                        + ". It takes effect on the next boot.");
              } else {
                System.out.println(
                    "[PumpkinLib] Could NOT write the robot identity file: "
                        + PersistentStore.persistent().lastError().orElse("unknown error"));
              }
            })
        .ignoringDisable(true)
        .withName("RobotIdentity.assign(" + id + ")");
  }

  /**
   * Resets all identity state. Test-only.
   *
   * <p>Mirrors {@code Clock.resetForTest()}; without it a JUnit class cannot exercise two different
   * strategy tables, because {@link Builder#done()} deliberately refuses to run twice.
   */
  public static void resetForTest() {
    s_resolver = null;
    s_resolution = null;
  }

  /**
   * Installs an already-built resolver and resolves immediately. Test and tooling entry point.
   *
   * @param resolver the strategy table
   * @param inputs what the platform can see; pass a hand-built {@link IdentityResolver.Inputs} to
   *     test a resolution without an HAL
   * @return the resolution
   */
  public static IdentityResolver.Resolution installForTest(
      IdentityResolver resolver, IdentityResolver.Inputs inputs) {
    s_resolver = resolver;
    s_resolution = resolver.resolve(inputs);
    return s_resolution;
  }

  /**
   * Reads the four platform facts the resolution needs.
   *
   * @return the inputs for this machine right now
   */
  private static IdentityResolver.Inputs gatherInputs(boolean persistentFileEnabled) {
    Optional<String> file =
        persistentFileEnabled
            ? PersistentStore.persistent().readText(kIdentityFileName)
            : Optional.empty();
    // Every Platform read is defended, including against LinkageError: serialNumber() and
    // comments() are JNI calls, and on a machine with no WPILib natives the HAL's static
    // initialiser fails outright. An unreadable strategy must degrade to "this platform cannot tell
    // me" and fall through to the next one -- that is the whole reason those two return Optional.
    return new IdentityResolver.Inputs(
        file, safeRead(Platform::comments), safeRead(Platform::serialNumber), safeIsSimulation());
  }

  private static Optional<String> safeRead(Supplier<Optional<String>> read) {
    try {
      Optional<String> value = read.get();
      return value == null ? Optional.empty() : value;
    } catch (RuntimeException | LinkageError e) {
      return Optional.empty();
    }
  }

  private static boolean safeIsSimulation() {
    try {
      return Platform.isSimulation();
    } catch (RuntimeException | LinkageError e) {
      // If we cannot even tell, assume hardware: resolving to SIM on a real robot is the one
      // outcome that guarantees wrong gearing, wrong limits and wrong offsets.
      return false;
    }
  }

  private static String callerLocation() {
    // Walk out past this class to the first frame that is not RobotIdentity itself. Chosen over a
    // fixed stack depth because pick() is reachable through overlay() and through user code.
    return StackWalker.getInstance()
        .walk(
            frames ->
                frames
                    .filter(f -> !f.getClassName().equals(RobotIdentity.class.getName()))
                    .findFirst()
                    .map(f -> f.getClassName() + "." + f.getMethodName() + " (" + f.getFileName()
                        + ":" + f.getLineNumber() + ")")
                    .orElse("unknown"));
  }

  /**
   * Declarative registration of the identity strategies.
   *
   * <p>Order of registration matters only within a strategy: comment rules are tried in the order
   * they were declared. The strategies themselves always run in the order documented on
   * {@link IdentityResolver}, regardless of the order you call these methods in.
   */
  public static final class Builder {

    private boolean m_persistentFile = true;
    private final Map<String, RobotId> m_bySerial = new LinkedHashMap<>();
    private final List<IdentityResolver.CommentRule> m_byComment = new ArrayList<>();
    private RobotId m_simId = RobotId.SIM;
    private RobotId m_fallback = RobotId.COMP;

    private Builder() {}

    /**
     * Registers an exact controller serial-number match.
     *
     * <p>The serial is printed to the log the first time a robot boots, so recording it costs one
     * copy-paste and then never needs touching again.
     *
     * @param serial the serial number, matched exactly after trimming
     * @param id the identity for that controller
     * @return this builder
     * @throws IllegalArgumentException if {@code serial} is blank, {@code id} is null, or that
     *     serial was already registered to a different identity
     */
    public Builder bySerial(String serial, RobotId id) {
      if (serial == null || serial.isBlank()) {
        throw new IllegalArgumentException(
            "RobotIdentity.bySerial(serial, id): serial was "
                + (serial == null ? "null" : "blank")
                + ". Copy it from the boot log line \"roboRIO serial: ...\".");
      }
      if (id == null) {
        throw new IllegalArgumentException(
            "RobotIdentity.bySerial(\"" + serial + "\", id): id was null.");
      }
      String key = serial.trim();
      RobotId existing = m_bySerial.put(key, id);
      if (existing != null && existing != id) {
        throw new IllegalArgumentException(
            "RobotIdentity.bySerial(\""
                + key
                + "\", "
                + id
                + "): that serial is already registered to "
                + existing
                + ". One controller is one robot; remove whichever line is wrong.");
      }
      return this;
    }

    /**
     * Registers a case-insensitive substring of the roboRIO web-dashboard comments field.
     *
     * <p>This is the strategy a student can use with no code and no SSH: open the roboRIO web
     * dashboard, type {@code practice} into the comments box, reboot.
     *
     * @param substring the substring to look for
     * @param id the identity for a robot whose comments contain it
     * @return this builder
     * @throws IllegalArgumentException if {@code substring} is blank or {@code id} is null
     */
    public Builder byComment(String substring, RobotId id) {
      m_byComment.add(new IdentityResolver.CommentRule(substring.trim(), id));
      return this;
    }

    /**
     * Whether to read {@code <persistentDir>/pumpkin/robot-id}. On by default.
     *
     * <p>Turn it off only if you want the comments field or the serial table to be authoritative
     * even on a robot that has an identity file — for example while debugging why a robot insists
     * it is the practice bot.
     *
     * @param enabled whether strategy 1 runs
     * @return this builder
     */
    public Builder byPersistentFile(boolean enabled) {
      m_persistentFile = enabled;
      return this;
    }

    /**
     * The identity used when running in simulation. Defaults to {@link RobotId#SIM}.
     *
     * <p>Setting this to a hardware identity is legitimate and useful — it is how you simulate the
     * practice bot's gearing — but it means sim runs hardware constants, so say it explicitly.
     *
     * @param id the identity for simulation
     * @return this builder
     * @throws IllegalArgumentException if {@code id} is null
     */
    public Builder simIs(RobotId id) {
      if (id == null) {
        throw new IllegalArgumentException("RobotIdentity.simIs(id): id was null.");
      }
      m_simId = id;
      return this;
    }

    /**
     * The identity used when nothing matched. Defaults to {@link RobotId#COMP}.
     *
     * <p>Fail <b>safe</b>: a mystery robot should behave like the comp bot, because the comp bot's
     * constants are the ones that have been tested. {@code pumpkinCheckDeploy} hard-fails, with no
     * override, if this is {@link RobotId#SIM} — a real robot running simulation constants has the
     * wrong gearing, the wrong limits and the wrong offsets, so that build cannot produce a working
     * robot and blocking the deploy costs nothing.
     *
     * <p>Using the fallback also raises an {@code ERROR} / {@code BLOCKS_MATCH} alert naming every
     * strategy that was tried and what it saw; see {@link IdentityResolver.Resolution#describe()}.
     *
     * @param id the last-resort identity
     * @return this builder
     * @throws IllegalArgumentException if {@code id} is null
     */
    public Builder fallback(RobotId id) {
      if (id == null) {
        throw new IllegalArgumentException(
            "RobotIdentity.fallback(id): id was null. Pass RobotId.COMP — a mystery robot should"
                + " behave like the comp bot.");
      }
      m_fallback = id;
      return this;
    }

    /**
     * Completes registration and resolves the identity immediately.
     *
     * <p>Resolution happens here, once, so that {@link #current()} is a field read on every
     * subsequent call and so that the strategy reads (a file, two HAL strings) happen at a known
     * point in boot rather than at whatever moment the first config constant is touched.
     *
     * @throws IllegalStateException if identity was already configured — two strategy tables in one
     *     JVM means two different answers to "which robot is this", and silently letting the second
     *     win is exactly the ambiguity this class exists to remove. Use {@link #resetForTest()} in
     *     tests.
     */
    public void done() {
      if (s_resolution != null) {
        throw new IllegalStateException(
            "RobotIdentity.configure()...done() was called twice. The identity resolved to "
                + s_resolution.id()
                + " via "
                + s_resolution.source()
                + ".\n  Two strategy tables mean two answers to \"which robot is this\". Call"
                + " RobotIds.register() from exactly one place\n  (the template calls it from the"
                + " Robot constructor). In tests, call RobotIdentity.resetForTest() first.");
      }
      IdentityResolver resolver =
          new IdentityResolver(m_persistentFile, m_bySerial, m_byComment, m_simId, m_fallback);
      s_resolver = resolver;
      s_resolution = resolver.resolve(gatherInputs(m_persistentFile));
    }
  }
}

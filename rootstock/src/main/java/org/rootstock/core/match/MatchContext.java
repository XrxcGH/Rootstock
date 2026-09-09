package org.rootstock.core.match;

import static edu.wpi.first.units.Units.Seconds;

import edu.wpi.first.units.measure.Time;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.DriverStation.Alliance;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import org.rootstock.core.compat.Platform;

/**
 * THE single {@link DriverStation} reader in Rootstock.
 *
 * <p>Nothing else in {@code org.rootstock} — not even {@code org.rootstock.core.compat} — may
 * name {@code DriverStation}. ArchUnit rule 10 ({@code onlyMatchReadsDriverStation}) enforces it,
 * and it is the narrowest allowlist in the library: {@code core.compat} is <i>not</i> on it. When
 * 2027 moves or renames the DS surface, this is the one file that changes. If a second package ever
 * needs the DriverStation, the answer is a new method here, never a new allowlist entry.
 *
 * <p><b>The problem this class exists to solve.</b> {@code DriverStation.getAlliance()} returns an
 * <i>empty</i> {@code Optional} until the DS connects. Read it in a constructor, in an
 * initialisation method, or in an auto-chooser lambda that evaluates early, and you get the wrong
 * alliance and a mirrored autonomous. Nothing in WPILib caches it at the right moment or warns that
 * you read it too early. This is the highest-frequency competition-day failure in FRC and it costs
 * small teams entire matches. {@link #alliance()} latches on the DS-connect edge and never goes
 * empty again, and {@link #isRed()} is the {@code BooleanSupplier} the auto builder wants.
 *
 * <p><b>Spellings mirror WPILib exactly</b> ({@code isFMSAttached}, not {@code isFmsAttached}) so
 * that a student who learns this API has learned the WPILib one. "Never hide WPILib" applies to
 * method names too. The one deliberate exception is {@link #isDiagnostics()}, which exists because
 * WPILib's own name for that mode changes in 2027.
 *
 * <p><b>Cost.</b> Every accessor on this class is a single {@code volatile} field read once
 * {@link #periodic()} has run — no map lookups, no {@code Optional} allocation on the boolean paths,
 * nothing that anybody is later tempted to "optimise away". {@link FmsPolicy#tunablesLocked()} is
 * called on <i>every</i> tunable read in the library and that is why. Before the first
 * {@code periodic()} the accessors fall through to a live DS read, so a value read during
 * construction is correct rather than a default.
 */
public final class MatchContext {

  private MatchContext() {}

  // -------------------------------------------------------------------------------------------
  // Cached state. Written only by periodic()/poll(), read by everything.
  //
  // volatile, not synchronized: these are independent booleans, no reader needs a consistent
  // snapshot across two of them, and a lock on the hot path is the thing somebody deletes later.
  // -------------------------------------------------------------------------------------------

  /** Null until the DS first reports an alliance; never returns to null afterwards. */
  private static volatile Alliance s_alliance;

  private static volatile int s_station = -1;

  private static volatile boolean s_fmsAttached;
  private static volatile boolean s_dsAttached;
  private static volatile boolean s_disabled = true;
  private static volatile boolean s_enabled;
  private static volatile boolean s_autonomous;
  private static volatile boolean s_teleop;
  private static volatile boolean s_estopped;
  private static volatile boolean s_diagnostics;

  private static volatile MatchPhase s_phase = MatchPhase.DISABLED;

  /** Null when no FMS is supplying match identity. Refreshed by {@link #poll()}. */
  private static volatile MatchInfo s_matchInfo;

  private static volatile double s_matchTimeSeconds = -1.0;

  /** False until {@link #periodic()} has run once; gates the fall-through to a live DS read. */
  private static volatile boolean s_periodicRan;

  private static volatile boolean s_matchStarted;
  private static volatile boolean s_matchEnded;

  /** Cross-boot flag, backed by the persistent store. See {@link #everFMSAttached()}. */
  private static volatile boolean s_everFmsAttached;

  private static volatile boolean s_everFmsLoaded;

  private static volatile String s_buildSha = "nogit";

  private static volatile Runnable s_derivedPublisher = () -> {};

  // Triggers are built lazily and cached: constructing one touches the CommandScheduler, which
  // reports usage to the HAL, and a static initialiser that needs the HAL breaks headless tests.
  private static volatile Trigger s_onDsAttach;
  private static volatile Trigger s_onFmsAttach;
  private static volatile Trigger s_onAllianceKnown;
  private static volatile Trigger s_onMatchStart;
  private static volatile Trigger s_onMatchEnd;

  /** Relative to {@link Platform#persistentDir()}. Read by the deploy gate, written on FMS attach. */
  private static final String kStateFileRelative = "rootstock/target-state.json";

  // -------------------------------------------------------------------------------------------
  // Alliance. The reason this class exists.
  // -------------------------------------------------------------------------------------------

  /**
   * The alliance, latched on the DS-connect rising edge.
   *
   * <p>Empty only before the DS has ever connected. Once latched it never returns to empty, so an
   * auto that starts three seconds after a momentary DS dropout still flips its paths the right
   * way. It <i>is</i> re-read on a subsequent DS-attach edge, because being moved from red 2 to
   * blue 1 between matches without a reboot is normal and the stale value would be worse than no
   * value.
   *
   * @return the latched alliance, or empty if the DS has never connected
   */
  public static Optional<Alliance> alliance() {
    return Optional.ofNullable(s_alliance);
  }

  /**
   * Convenience for path flipping: {@code AutoBuilder.configure(..., MatchContext::isRed, drive)}.
   *
   * <p>False (i.e. blue) until the alliance is known, because a boolean has nowhere to put "I don't
   * know". Gate on {@link #allianceKnown()} when the difference matters — autonomous must refuse to
   * run rather than guess.
   *
   * @return true when the latched alliance is red
   */
  public static boolean isRed() {
    return s_alliance == Alliance.Red;
  }

  /**
   * The blue counterpart of {@link #isRed()}, with the same "false means also unknown" caveat.
   *
   * @return true when the latched alliance is blue
   */
  public static boolean isBlue() {
    return s_alliance == Alliance.Blue;
  }

  /**
   * Whether the alliance has been latched yet.
   *
   * <p>This is the gate. Autonomous must not run when this is false: every path is about to be
   * mirrored the wrong way, and a robot that sits still is recoverable where a robot that drives
   * into its own alliance wall is not.
   *
   * @return true once the DS has reported an alliance at least once
   */
  public static boolean allianceKnown() {
    return s_alliance != null;
  }

  /**
   * Latched-and-known red, for the default LED state table.
   *
   * @return true when the alliance is known and it is red
   */
  public static boolean allianceKnownAndRed() {
    return s_alliance == Alliance.Red;
  }

  /**
   * Latched-and-known blue, for the default LED state table.
   *
   * @return true when the alliance is known and it is blue
   */
  public static boolean allianceKnownAndBlue() {
    return s_alliance == Alliance.Blue;
  }

  /**
   * Driver station position 1, 2 or 3, latched alongside the alliance.
   *
   * @return the station number, or empty if the DS has never connected
   */
  public static OptionalInt station() {
    int station = s_station;
    return station >= 1 ? OptionalInt.of(station) : OptionalInt.empty();
  }

  // -------------------------------------------------------------------------------------------
  // Attachment.
  // -------------------------------------------------------------------------------------------

  /**
   * Whether a field management system is attached — i.e. whether this is a real match.
   *
   * <p>Constant time: one {@code volatile} boolean read. {@link FmsPolicy} calls this on every
   * tunable read in the library, so it must stay that way.
   *
   * @return true when the FMS is attached
   */
  public static boolean isFMSAttached() {
    return s_periodicRan ? s_fmsAttached : DriverStation.isFMSAttached();
  }

  /**
   * Whether the driver station is attached.
   *
   * @return true when a DS is connected
   */
  public static boolean isDSAttached() {
    return s_periodicRan ? s_dsAttached : DriverStation.isDSAttached();
  }

  /**
   * Has this <i>robot</i> ever been FMS-attached, across boots?
   *
   * <p>Backed by {@code Platform.persistentDir()/rootstock/target-state.json}, written on the
   * FMS-attach rising edge. The deploy gate reads it to decide whether a dirty working tree should
   * block a deploy: on a shop robot it should not, on a robot that has been on a field it should
   * warn loudly.
   *
   * <p><b>Never throws.</b> A missing, unreadable or corrupt file is {@code false}, because an
   * unreadable file must never be a reason to refuse to fix the robot.
   *
   * @return true if this robot has been FMS-attached at least once in its life
   */
  public static boolean everFMSAttached() {
    if (!s_everFmsLoaded) {
      loadEverFmsAttached();
    }
    return s_everFmsAttached;
  }

  // -------------------------------------------------------------------------------------------
  // Enable state. These are the calls that replace DriverStation.* everywhere else in the library.
  // -------------------------------------------------------------------------------------------

  /**
   * Whether the robot is disabled.
   *
   * @return true when not enabled
   */
  public static boolean isDisabled() {
    return s_periodicRan ? s_disabled : DriverStation.isDisabled();
  }

  /**
   * Whether the robot is enabled.
   *
   * @return true when enabled in any mode
   */
  public static boolean isEnabled() {
    return s_periodicRan ? s_enabled : DriverStation.isEnabled();
  }

  /**
   * Whether the DS mode selector is on Autonomous.
   *
   * <p>Mirrors {@code DriverStation.isAutonomous()} exactly, which means it is <b>true while
   * disabled</b> in the pre-match hold. Use {@link #phase()} when you want the answer that accounts
   * for that; this method exists so the WPILib semantics are still reachable.
   *
   * @return true when the mode selector is on Autonomous
   */
  public static boolean isAutonomous() {
    return s_periodicRan ? s_autonomous : DriverStation.isAutonomous();
  }

  /**
   * Whether the DS mode selector is on Teleoperated. Same disabled-mode caveat as
   * {@link #isAutonomous()}.
   *
   * @return true when the mode selector is on Teleoperated
   */
  public static boolean isTeleop() {
    return s_periodicRan ? s_teleop : DriverStation.isTeleop();
  }

  /**
   * Whether the robot is emergency-stopped.
   *
   * @return true when e-stopped
   */
  public static boolean isEStopped() {
    return s_periodicRan ? s_estopped : DriverStation.isEStopped();
  }

  /**
   * The DS's "Test" mode in 2026; renamed "Utility" in 2027.
   *
   * <p>Callers say {@code isDiagnostics()} and never have to care. This is the ONLY spelling in
   * Rootstock — {@code Platform} deliberately does not have a {@code diagnosticsMode()} twin,
   * because two spellings for one concept is how half the library ends up on the wrong side of a
   * rename.
   *
   * @return true when the DS is in its diagnostics/test tab
   */
  public static boolean isDiagnostics() {
    return s_periodicRan ? s_diagnostics : DriverStation.isTest();
  }

  /**
   * The current phase, with the precedence applied once, in one place.
   *
   * @return the derived phase; never null
   * @see MatchPhase
   */
  public static MatchPhase phase() {
    return s_periodicRan ? s_phase : derivePhase();
  }

  // -------------------------------------------------------------------------------------------
  // HID slot verification, for DsMonitor.
  // -------------------------------------------------------------------------------------------

  /**
   * The controller name the DS reports in a slot.
   *
   * <p>{@code DsMonitor} cross-checks declared HID ports against this and raises a blocking alert
   * naming the expected and the actual controller when a gamepad lands in the wrong slot. Half the
   * bindings being dead genuinely means do not take the field.
   *
   * @param port the DS joystick port, 0-5
   * @return the reported name, or {@code ""} when the slot is empty or the port is out of range
   */
  public static String joystickName(int port) {
    if (port < 0 || port >= DriverStation.kJoystickPorts) {
      return "";
    }
    String name = DriverStation.getJoystickName(port);
    return name == null ? "" : name;
  }

  /**
   * Whether the DS reports the controller in a slot as an Xbox-class device.
   *
   * @param port the DS joystick port, 0-5
   * @return false when the slot is empty or the port is out of range
   */
  public static boolean joystickIsXbox(int port) {
    if (port < 0 || port >= DriverStation.kJoystickPorts) {
      return false;
    }
    return DriverStation.getJoystickIsXbox(port);
  }

  /**
   * Whether anything is plugged into a slot.
   *
   * @param port the DS joystick port, 0-5
   * @return false when the slot is empty or the port is out of range
   */
  public static boolean joystickConnected(int port) {
    if (port < 0 || port >= DriverStation.kJoystickPorts) {
      return false;
    }
    return DriverStation.isJoystickConnected(port);
  }

  // -------------------------------------------------------------------------------------------
  // Match identity.
  // -------------------------------------------------------------------------------------------

  /**
   * Event and match identity, present only when the FMS supplies it.
   *
   * <p>Refreshed by {@link #poll()} rather than {@link #periodic()}: the event name is a string
   * compare and it cannot change within a match, so it does not belong in the every-loop path.
   *
   * @return the current match identity, or empty off the field
   */
  public static Optional<MatchInfo> match() {
    return Optional.ofNullable(s_matchInfo);
  }

  /**
   * Time remaining in the current match period, approximate.
   *
   * <p><b>DO NOT use for control decisions.</b> The FMS updates this coarsely and the DS
   * interpolates; a shot timed off it will be late on the field and early in the shop. It is
   * exposed because pit displays and drivers want it.
   *
   * @return remaining time, or zero seconds when the DS is not reporting one
   */
  public static Time matchTimeRemaining() {
    double t = s_periodicRan ? s_matchTimeSeconds : DriverStation.getMatchTime();
    // WPILib returns -1 for "unknown". Clamping to zero keeps the unit sane for a display; the
    // "is it known" question is answered by match().isPresent(), not by a negative duration.
    return Seconds.of(Math.max(0.0, t));
  }

  /**
   * The log file stem: {@code "CURIE_Q34_a1b2c3d"} — event, match, git sha.
   *
   * <p>AdvantageKit's {@code WPILOGWriter} does not inherit {@code DataLogManager}'s FMS rename and
   * produces hash names like {@code akit_6497c321bb716896.wpilog}. Core renames on the FMS-attach
   * edge to this, so "the log from Qual 34" is a thing you can find.
   *
   * @return a filename-safe stem; {@code "NoEvent_NoMatch_<sha>"} when no FMS data is present
   */
  public static String logFileStem() {
    MatchInfo info = s_matchInfo;
    String event;
    String label;
    if (info != null && info.isKnown()) {
      event = info.eventName().isEmpty() ? "NoEvent" : info.eventName().toUpperCase(Locale.ROOT);
      label = info.shortLabel();
    } else {
      event = "NoEvent";
      label = "NoMatch";
    }
    return sanitiseForFilename(event) + "_" + sanitiseForFilename(label) + "_" + s_buildSha;
  }

  /**
   * Push the deployed git sha in, so {@link #logFileStem()} can name it.
   *
   * <p>A <b>push</b> registration rather than a query, deliberately: the build/deploy identity lives
   * in the deploy package and {@code core.match} must not grow an arrow to it. Whoever owns
   * {@code DeployInfo} calls this once at boot. Until it does, the stem carries {@code "nogit"},
   * which is a visible fact rather than a silent gap.
   *
   * @param sha the short git sha; null or blank restores {@code "nogit"}
   */
  public static void setBuildSha(String sha) {
    s_buildSha = (sha == null || sha.isBlank()) ? "nogit" : sanitiseForFilename(sha.trim());
  }

  /**
   * The offline pre-loaded schedule entry for the next match, if a schedule was deployed.
   *
   * @return the next scheduled match, or empty when no schedule file is present
   * @see MatchSchedule
   */
  public static Optional<MatchInfo> scheduledMatch() {
    return MatchSchedule.nextScheduled();
  }

  // -------------------------------------------------------------------------------------------
  // Triggers.
  // -------------------------------------------------------------------------------------------

  /**
   * Fires when the driver station connects.
   *
   * <p>These are level conditions, as WPILib triggers are; bind with {@code .onTrue(...)} to act on
   * the edge. The instances are cached, so calling this in a loop is free after the first call.
   *
   * @return a trigger that is true while the DS is attached
   */
  public static Trigger onDsAttach() {
    Trigger t = s_onDsAttach;
    if (t == null) {
      t = new Trigger(MatchContext::isDSAttached);
      s_onDsAttach = t;
    }
    return t;
  }

  /**
   * Fires when the FMS connects — the moment the robot learns it is at a real event.
   *
   * @return a trigger that is true while the FMS is attached
   */
  public static Trigger onFmsAttach() {
    Trigger t = s_onFmsAttach;
    if (t == null) {
      t = new Trigger(MatchContext::isFMSAttached);
      s_onFmsAttach = t;
    }
    return t;
  }

  /**
   * Fires once the alliance has been latched. Latching is one-way, so this never falls back.
   *
   * @return a trigger that is true once the alliance is known
   */
  public static Trigger onAllianceKnown() {
    Trigger t = s_onAllianceKnown;
    if (t == null) {
      t = new Trigger(MatchContext::allianceKnown);
      s_onAllianceKnown = t;
    }
    return t;
  }

  /**
   * Fires on the first enable after the FMS attaches, and stays true for the rest of the session.
   *
   * <p>Latched rather than momentary so that a subsystem constructed late still sees that the match
   * has begun. Bind with {@code .onTrue(...)} for one-shot behaviour.
   *
   * @return a trigger that is true from match start onwards
   */
  public static Trigger onMatchStart() {
    Trigger t = s_onMatchStart;
    if (t == null) {
      t = new Trigger(() -> s_matchStarted);
      s_onMatchStart = t;
    }
    return t;
  }

  /**
   * Fires when a started match returns to disabled, and stays true afterwards.
   *
   * @return a trigger that is true from match end onwards
   */
  public static Trigger onMatchEnd() {
    Trigger t = s_onMatchEnd;
    if (t == null) {
      t = new Trigger(() -> s_matchEnded);
      s_onMatchEnd = t;
    }
    return t;
  }

  // -------------------------------------------------------------------------------------------
  // Lifecycle. Two methods, two different rates, and the split is deliberate.
  // -------------------------------------------------------------------------------------------

  /**
   * EVERY loop, called by {@code RootstockRobot}.
   *
   * <p>~20 µs of boolean reads plus edge latching: alliance latch, FMS/DS attach edges,
   * {@link #everFMSAttached()} persistence, phase transitions. This is <b>not</b> a slice, because
   * its entire value is catching an edge on the cycle it happens — a slice would notice the FMS
   * attach up to eleven loops late and rename the log after it had already been opened.
   *
   * <p>Public rather than package-private, unlike the design sketch, because the caller
   * ({@code RootstockRobot}, in {@code org.rootstock.core}) is in a different package and Java has no
   * narrower visibility that reaches it.
   */
  public static void periodic() {
    boolean fms = DriverStation.isFMSAttached();
    boolean ds = DriverStation.isDSAttached();
    boolean estopped = DriverStation.isEStopped();
    boolean diagnostics = DriverStation.isTest();
    boolean enabled = DriverStation.isEnabled();

    boolean dsRisingEdge = ds && !s_dsAttached;
    boolean fmsRisingEdge = fms && !s_fmsAttached;

    s_fmsAttached = fms;
    s_dsAttached = ds;
    s_estopped = estopped;
    s_diagnostics = diagnostics;
    s_enabled = enabled;
    s_disabled = DriverStation.isDisabled();
    s_autonomous = DriverStation.isAutonomous();
    s_teleop = DriverStation.isTeleop();
    s_matchTimeSeconds = DriverStation.getMatchTime();

    // Alliance latch. Latch on first sight, and re-latch on a DS reconnect: being moved from red 2
    // to blue 1 between matches without a reboot is routine, and a stale latch is worse than none.
    if (s_alliance == null || dsRisingEdge) {
      Optional<Alliance> reported = DriverStation.getAlliance();
      if (reported.isPresent()) {
        s_alliance = reported.get();
        s_station = DriverStation.getLocation().orElse(-1);
      }
    }

    s_phase = derivePhase();

    if (fmsRisingEdge) {
      persistEverFmsAttached();
    }

    if (fms && enabled && !s_matchStarted) {
      s_matchStarted = true;
    }
    if (s_matchStarted && !enabled && !s_matchEnded) {
      s_matchEnded = true;
    }

    s_periodicRan = true;
  }

  /**
   * ONE SLICE of the shared round-robin: the derived state that is not worth an every-loop read,
   * plus the {@code /Rootstock/Match/} publishing.
   *
   * <p>Registered as {@code SliceScheduler.register("Match", MatchContext::poll)}. There is no
   * wall-clock gate here and there must never be one — a 4 Hz wall-clock gate fires on different
   * cycles under 50× replay and turns every replay diff into noise.
   *
   * <p>{@code core.match} cannot import a logger (ArchUnit rules 1c and 9), so the publishing half
   * is an installed {@link #setDerivedPublisher(Runnable)} callback that the telemetry domain
   * supplies. This method refreshes the state that callback reads and then runs it.
   */
  public static void poll() {
    if (DriverStation.isFMSAttached()) {
      s_matchInfo =
          new MatchInfo(
              DriverStation.getEventName(),
              DriverStation.getMatchType(),
              DriverStation.getMatchNumber(),
              DriverStation.getReplayNumber());
    } else {
      // Off the field, identity is genuinely absent. Do not keep a stale record from a prior match.
      s_matchInfo = null;
    }
    s_derivedPublisher.run();
  }

  /**
   * Install the callback that publishes derived match state under {@code /Rootstock/Match/}.
   *
   * <p>Push registration, for the same reason as {@link #setBuildSha(String)}: no arrow may point
   * out of {@code core}. Called once, by the telemetry domain, at boot.
   *
   * @param publisher the publishing action; null installs a no-op
   */
  public static void setDerivedPublisher(Runnable publisher) {
    s_derivedPublisher = publisher == null ? () -> {} : publisher;
  }

  /**
   * Restores every latch and cache to its boot state. For tests only.
   *
   * <p>Alliance latching, FMS edges and match start/end are one-way by design, which makes a
   * two-scenario unit test impossible without an explicit reset. Nothing on the robot calls this.
   */
  public static void resetForTest() {
    s_alliance = null;
    s_station = -1;
    s_fmsAttached = false;
    s_dsAttached = false;
    s_disabled = true;
    s_enabled = false;
    s_autonomous = false;
    s_teleop = false;
    s_estopped = false;
    s_diagnostics = false;
    s_phase = MatchPhase.DISABLED;
    s_matchInfo = null;
    s_matchTimeSeconds = -1.0;
    s_periodicRan = false;
    s_matchStarted = false;
    s_matchEnded = false;
    s_everFmsAttached = false;
    s_everFmsLoaded = false;
    s_buildSha = "nogit";
    s_derivedPublisher = () -> {};
  }

  /**
   * One line naming everything a person asks about at 11pm in the pit.
   *
   * @return e.g. {@code "MatchContext: TELEOP, alliance=Red station=2, FMS=true DS=true, CURIE Q34"}
   */
  public static String describe() {
    Alliance a = s_alliance;
    MatchInfo info = s_matchInfo;
    return "MatchContext: "
        + phase()
        + ", alliance="
        + (a == null ? "UNKNOWN" : a)
        + " station="
        + (s_station >= 1 ? Integer.toString(s_station) : "?")
        + ", FMS="
        + isFMSAttached()
        + " DS="
        + isDSAttached()
        + ", "
        + (info == null ? "no match data" : info.describe());
  }

  // -------------------------------------------------------------------------------------------
  // Internals.
  // -------------------------------------------------------------------------------------------

  private static MatchPhase derivePhase() {
    if (DriverStation.isEStopped()) {
      return MatchPhase.ESTOPPED;
    }
    if (DriverStation.isTest()) {
      return MatchPhase.DIAGNOSTICS;
    }
    if (DriverStation.isDisabled()) {
      return MatchPhase.DISABLED;
    }
    if (DriverStation.isAutonomous()) {
      return MatchPhase.AUTONOMOUS;
    }
    if (DriverStation.isTeleop()) {
      return MatchPhase.TELEOP;
    }
    return MatchPhase.DISABLED;
  }

  /**
   * Reads the cross-boot flag. Swallows everything: an unreadable file is "no information", and no
   * failure here may ever propagate into the robot loop.
   */
  private static void loadEverFmsAttached() {
    boolean value = false;
    try {
      Path file = Platform.persistentDir().resolve(kStateFileRelative);
      if (Files.isReadable(file)) {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        // A three-key file written only by this class. A hand rolled contains-check is used instead
        // of a JSON parser so that a truncated or half-written file degrades to false rather than
        // throwing on a competition field.
        value = text.replace(" ", "").contains("\"everFmsAttached\":true");
      }
    } catch (RuntimeException | java.io.IOException e) {
      value = false;
    }
    s_everFmsAttached = value;
    s_everFmsLoaded = true;
  }

  /** Writes the cross-boot flag on the FMS-attach rising edge. Best effort; never throws. */
  private static void persistEverFmsAttached() {
    s_everFmsAttached = true;
    s_everFmsLoaded = true;
    try {
      Path file = Platform.persistentDir().resolve(kStateFileRelative);
      Files.createDirectories(file.getParent());
      MatchInfo info = s_matchInfo;
      String event =
          info == null || info.eventName().isEmpty()
              ? DriverStation.getEventName()
              : info.eventName();
      // Instant.now() is a wall-clock DATE for file provenance, written at most once per match on a
      // rising edge. design/04's R2 bans wall clocks in CONTROL LOGIC, where a replay-divergent
      // clock corrupts a control loop; a human-readable date in a JSON file is neither control
      // logic nor replayed. If rule 3 is ever extended to name Instant.now, this call needs an
      // explicit exemption rather than a rewrite -- there is no replay-safe source of a calendar
      // date, and the alternative is a file nobody can date.
      String json =
          "{\"everFmsAttached\": true, \"lastEvent\": \""
              + escapeJson(event == null ? "" : event)
              + "\", \"at\": \""
              + Instant.now()
              + "\"}\n";
      Files.writeString(file, json, StandardCharsets.UTF_8);
    } catch (RuntimeException | java.io.IOException e) {
      // Deliberately silent. A read-only or full filesystem must not take the robot down mid-match,
      // and the only consequence is that the deploy gate has less information than it could have.
    }
  }

  private static String escapeJson(String s) {
    return s.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static String sanitiseForFilename(String s) {
    StringBuilder out = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      out.append(Character.isLetterOrDigit(c) || c == '-' ? c : '-');
    }
    String result = out.toString();
    return result.isEmpty() ? "unknown" : result;
  }
}

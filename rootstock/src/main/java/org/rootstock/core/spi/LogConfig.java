package org.rootstock.core.spi;

// D26's downward-crossing-value package (ArchUnit rule 9). Telemetry (design/04 section 2.2b) still
// owns the field set and every field's semantics; only the package moved, by D31.
//
// RobotMode and Tier are declared in THIS package (D33), so there is no import for them and no
// arrow out of core.

import java.util.Objects;

/**
 * How Rootstock configures AdvantageKit's {@code Logger}, as an immutable value.
 *
 * <p><b>IMMUTABLE.</b> Every {@code with*()} returns a new instance; there is no setter, no public
 * field, no public constructor and no Builder. The library forbids mutable config everywhere else
 * (principle 6, D1a), and the front-door type is the worst possible place for an exception: a config
 * object that can be mutated after {@code start()} is a config object that can silently disagree with
 * the log's own provenance metadata.
 *
 * <p><b>Two factories, and no third (D29).</b> {@link #defaults()} and {@link #adoptExistingLogger()}
 * are the entire construction surface, and the choice between them is the single decision a team must
 * make consciously — because a double {@code Logger.start()} is a crash, and the design refuses to
 * guess. An earlier revision had {@code create()} "detect an already-started Logger and adopt it";
 * D29 rejects auto-detection. The caller states it.
 *
 * <p><b>Thirteen fields, one {@code with*()} each.</b> A team that finds thirteen wither methods
 * verbose has {@link #defaults()} plus a one-line chain, which is the same keystroke count as the
 * {@code Consumer<LogConfig>} lambda this replaced. Nothing was taken away except the ability to
 * mutate the object after {@code RootstockLifecycle} has read it, which was never a feature.
 *
 * <p>Consumed by {@code RootstockLifecycle.create(LogConfig)} and by
 * {@code RootstockRobot(LogConfig)}.
 */
public final class LogConfig {

  private final boolean m_adoptExistingLogger;
  private final RobotMode m_mode;
  private final String m_wpilogFolder;
  private final String m_fallbackFolder;
  private final boolean m_compress;
  private final NtPolicy m_ntPublish;
  private final Tier m_minimumTier;
  private final double m_perCycleByteBudget;
  private final boolean m_captureConsole;
  private final boolean m_captureDriverStation;
  private final boolean m_ctreSignalLogger;
  private final boolean m_urcl;
  private final int m_minFreeMegabytes;
  private final boolean m_driverMirror;

  private LogConfig(
      boolean adoptExistingLogger,
      RobotMode mode,
      String wpilogFolder,
      String fallbackFolder,
      boolean compress,
      NtPolicy ntPublish,
      Tier minimumTier,
      double perCycleByteBudget,
      boolean captureConsole,
      boolean captureDriverStation,
      boolean ctreSignalLogger,
      boolean urcl,
      int minFreeMegabytes,
      boolean driverMirror) {
    m_adoptExistingLogger = adoptExistingLogger;
    m_mode = mode;
    m_wpilogFolder = wpilogFolder;
    m_fallbackFolder = fallbackFolder;
    m_compress = compress;
    m_ntPublish = ntPublish;
    m_minimumTier = minimumTier;
    m_perCycleByteBudget = perCycleByteBudget;
    m_captureConsole = captureConsole;
    m_captureDriverStation = captureDriverStation;
    m_ctreSignalLogger = ctreSignalLogger;
    m_urcl = urcl;
    m_minFreeMegabytes = minFreeMegabytes;
    m_driverMirror = driverMirror;
  }

  // =============================================================================================
  // The two factories (D29). There is no public constructor.
  // =============================================================================================

  /**
   * From scratch: {@code RootstockLifecycle} CONFIGURES AdvantageKit's {@code Logger} and STARTS it.
   *
   * <p>What a new robot project, and {@code RootstockTemplate}, want.
   *
   * @return the default configuration
   */
  public static LogConfig defaults() {
    return new LogConfig(
        false,
        RobotMode.REAL,
        "/U/logs",
        "/home/lvuser/logs",
        false,
        NtPolicy.OFF_ON_FMS,
        Tier.DEBUG,
        6_000.0,
        true,
        true,
        false,
        false,
        200,
        true);
  }

  /**
   * Attach: {@code Logger.start()} has ALREADY run in the team's own code.
   *
   * <p>{@code RootstockLifecycle} configures nothing that would restart it and never calls
   * {@code start()} a second time. This is the one a team adding {@code RootstockLifecycle} to an
   * EXISTING {@code LoggedRobot} wants — 9143 already extends {@code LoggedRobot} with its own
   * {@code Logger} setup, and being asked to unpick that is exactly the adoption cost the incremental
   * path exists to avoid.
   *
   * <p>Every other field keeps its {@link #defaults()} value, and the ones that describe receivers
   * RootstockLifecycle would otherwise install are simply not acted on.
   *
   * @return a configuration that adopts an already-started {@code Logger}
   */
  public static LogConfig adoptExistingLogger() {
    LogConfig d = defaults();
    return new LogConfig(
        true,
        d.m_mode,
        d.m_wpilogFolder,
        d.m_fallbackFolder,
        d.m_compress,
        d.m_ntPublish,
        d.m_minimumTier,
        d.m_perCycleByteBudget,
        d.m_captureConsole,
        d.m_captureDriverStation,
        d.m_ctreSignalLogger,
        d.m_urcl,
        d.m_minFreeMegabytes,
        d.m_driverMirror);
  }

  /**
   * Whether this configuration came from {@link #adoptExistingLogger()}.
   *
   * <p>Deliberately <b>not</b> a fourteenth field with a {@code with*()}: which factory you used is
   * decided once, at construction, and a wither would recreate exactly the ambiguity D29 removed.
   * {@code RootstockLifecycle} reads this to decide whether to call {@code Logger.start()}.
   *
   * @return true if {@code Logger.start()} has already been called by the team's own code
   */
  public boolean adoptsExistingLogger() {
    return m_adoptExistingLogger;
  }

  // =============================================================================================
  // The field set. Reading is by accessor, writing is by copy. One with*() per field, no
  // exceptions. Thirteen fields; the order below is design/04 section 2.2b's order.
  // =============================================================================================

  /**
   * The run mode. Default {@link RobotMode#REAL}; set from {@code Platform.isReal()} by
   * {@code RootstockLifecycle}, not by user code.
   *
   * @return the mode
   */
  public RobotMode mode() {
    return m_mode;
  }

  /**
   * A copy with a different mode.
   *
   * @param m the mode
   * @return a new configuration
   */
  public LogConfig withMode(RobotMode m) {
    return copy(Objects.requireNonNull(m, "LogConfig.withMode: mode must not be null"), m_wpilogFolder,
        m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier, m_perCycleByteBudget,
        m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl, m_minFreeMegabytes,
        m_driverMirror);
  }

  /**
   * Where {@code .wpilog} files are written when the USB stick is present. Default {@code "/U/logs"}
   * (2027: {@code "/u/logs"}).
   *
   * @return the folder
   */
  public String wpilogFolder() {
    return m_wpilogFolder;
  }

  /**
   * A copy with a different wpilog folder.
   *
   * @param folder the folder
   * @return a new configuration
   */
  public LogConfig withWpilogFolder(String folder) {
    return copy(m_mode, requireFolder(folder, "withWpilogFolder"), m_fallbackFolder, m_compress,
        m_ntPublish, m_minimumTier, m_perCycleByteBudget, m_captureConsole, m_captureDriverStation,
        m_ctreSignalLogger, m_urcl, m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Where {@code .wpilog} files are written when {@link #wpilogFolder()} is unavailable — no USB
   * stick, or a full one. Default {@code "/home/lvuser/logs"}.
   *
   * @return the folder
   */
  public String fallbackFolder() {
    return m_fallbackFolder;
  }

  /**
   * A copy with a different fallback folder.
   *
   * @param folder the folder
   * @return a new configuration
   */
  public LogConfig withFallbackFolder(String folder) {
    return copy(m_mode, m_wpilogFolder, requireFolder(folder, "withFallbackFolder"), m_compress,
        m_ntPublish, m_minimumTier, m_perCycleByteBudget, m_captureConsole, m_captureDriverStation,
        m_ctreSignalLogger, m_urcl, m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Whether logs are xz-compressed to {@code .wpilogxz}. Default false.
   *
   * <p>It solves USB capacity and download time and spends roboRIO CPU, which is the resource with no
   * escape hatch. Off until it is measured on real hardware.
   *
   * @return true if compression is on
   */
  public boolean compress() {
    return m_compress;
  }

  /**
   * A copy with compression on or off.
   *
   * @param on whether to compress
   * @return a new configuration
   */
  public LogConfig withCompress(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, on, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Governs AdvantageKit's {@code NT4Publisher} DATA RECEIVER — i.e. whether the full log stream is
   * mirrored to NetworkTables. Default {@link NtPolicy#OFF_ON_FMS}.
   *
   * <p>It has nothing to do with the driver mirror ({@link #driverMirror()}), which is written with
   * raw NT publishers and is NEVER suppressed.
   *
   * @return the policy
   */
  public NtPolicy ntPublish() {
    return m_ntPublish;
  }

  /**
   * A copy with a different NT publish policy.
   *
   * @param p the policy
   * @return a new configuration
   */
  public LogConfig withNtPublish(NtPolicy p) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress,
        Objects.requireNonNull(p, "LogConfig.withNtPublish: policy must not be null"), m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * The lowest {@link Tier} that is written. Default {@link Tier#DEBUG}; auto-raised to
   * {@link Tier#STANDARD} on FMS attach, and no higher.
   *
   * @return the minimum tier
   */
  public Tier minimumTier() {
    return m_minimumTier;
  }

  /**
   * A copy with a different minimum tier.
   *
   * @param t the tier
   * @return a new configuration
   */
  public LogConfig withMinimumTier(Tier t) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish,
        Objects.requireNonNull(t, "LogConfig.withMinimumTier: tier must not be null"),
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * The byte-budget governor's threshold, in bytes written per cycle. Default 6000.
   *
   * @return the budget in bytes per cycle
   */
  public double perCycleByteBudget() {
    return m_perCycleByteBudget;
  }

  /**
   * A copy with a different per-cycle byte budget.
   *
   * @param bytes the budget in bytes per cycle; must be finite and strictly positive
   * @return a new configuration
   * @throws IllegalArgumentException if {@code bytes} is not finite and strictly positive
   */
  public LogConfig withPerCycleByteBudget(double bytes) {
    if (!Double.isFinite(bytes) || bytes <= 0.0) {
      throw new IllegalArgumentException(
          "LogConfig.withPerCycleByteBudget: bytes was "
              + bytes
              + "; it must be finite and strictly positive. The default is 6000. "
              + "Fix: to turn the governor off, set it very high (e.g. 1e9) rather than to zero. "
              + "Zero would demote every demotable key on the first cycle.");
    }
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        bytes, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Whether console output is captured into the log. Default true.
   *
   * @return true if console capture is on
   */
  public boolean captureConsole() {
    return m_captureConsole;
  }

  /**
   * A copy with console capture on or off.
   *
   * <p>AdvantageKit's console capture is built in and on by default, and the only control is the
   * opt-<b>out</b> {@code Logger.disableConsoleCapture()}. Setting this to false therefore maps to
   * <i>calling</i> that method; there is no enable call.
   *
   * @param on whether to capture the console
   * @return a new configuration
   */
  public LogConfig withCaptureConsole(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, on, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Whether Driver Station inputs and joystick data are captured into the log. Default true.
   *
   * @return true if DS capture is on
   */
  public boolean captureDriverStation() {
    return m_captureDriverStation;
  }

  /**
   * A copy with Driver Station capture on or off.
   *
   * @param on whether to capture the Driver Station
   * @return a new configuration
   */
  public LogConfig withCaptureDriverStation(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, on, m_ctreSignalLogger, m_urcl, m_minFreeMegabytes,
        m_driverMirror);
  }

  /**
   * Whether CTRE's {@code SignalLogger} runs alongside, producing a {@code .hoot} sidecar. Default
   * false.
   *
   * @return true if the CTRE signal logger is on
   */
  public boolean ctreSignalLogger() {
    return m_ctreSignalLogger;
  }

  /**
   * A copy with the CTRE signal logger on or off.
   *
   * @param on whether to run the CTRE signal logger
   * @return a new configuration
   */
  public LogConfig withCtreSignalLogger(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, on, m_urcl,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Whether URCL (the REV sidecar logger) runs alongside. Default false.
   *
   * @return true if URCL is on
   */
  public boolean urcl() {
    return m_urcl;
  }

  /**
   * A copy with URCL on or off.
   *
   * @param on whether to run URCL
   * @return a new configuration
   */
  public LogConfig withUrcl(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, on,
        m_minFreeMegabytes, m_driverMirror);
  }

  /**
   * Below this much free space on the log volume, writing stops and a fault is raised. Default 200.
   *
   * <p>Stopping is deliberate: a full log volume that keeps being written to is how a roboRIO's
   * filesystem ends up read-only between qualification matches.
   *
   * @return the floor in megabytes
   */
  public int minFreeMegabytes() {
    return m_minFreeMegabytes;
  }

  /**
   * A copy with a different free-space floor.
   *
   * @param mb the floor in megabytes; must be non-negative
   * @return a new configuration
   * @throws IllegalArgumentException if {@code mb} is negative
   */
  public LogConfig withMinFreeMegabytes(int mb) {
    if (mb < 0) {
      throw new IllegalArgumentException(
          "LogConfig.withMinFreeMegabytes: mb was "
              + mb
              + "; it must be non-negative. The default is 200. "
              + "Fix: pass 0 to disable the free-space check entirely.");
    }
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        mb, m_driverMirror);
  }

  /**
   * Whether the {@code /Rootstock/Driver/**} NT mirror is published. Default true.
   *
   * <p>This is the small, hand-picked set of values a driver's dashboard shows, written with raw NT
   * publishers at 10 Hz. It is never suppressed by {@link #ntPublish()}, which governs the full log
   * stream and is a different thing entirely.
   *
   * @return true if the driver mirror is on
   */
  public boolean driverMirror() {
    return m_driverMirror;
  }

  /**
   * A copy with the driver mirror on or off.
   *
   * @param on whether to publish the driver mirror
   * @return a new configuration
   */
  public LogConfig withDriverMirror(boolean on) {
    return copy(m_mode, m_wpilogFolder, m_fallbackFolder, m_compress, m_ntPublish, m_minimumTier,
        m_perCycleByteBudget, m_captureConsole, m_captureDriverStation, m_ctreSignalLogger, m_urcl,
        m_minFreeMegabytes, on);
  }

  // =============================================================================================

  /** Whether AdvantageKit's full log stream is mirrored to NetworkTables. */
  public enum NtPolicy {
    /** Always publish. Convenient in the pit; expensive on a busy field. */
    ALWAYS,
    /** Publish unless FMS-attached. The default. */
    OFF_ON_FMS,
    /** Never publish. */
    NEVER
  }

  /**
   * A multi-line dump of every field, for the boot log.
   *
   * <p>Printing the config the robot actually booted with — rather than the one the source says it
   * should have — is how "why is nothing in my log" gets answered from the log itself.
   *
   * @return the human-readable configuration
   */
  public String describe() {
    return "LogConfig ("
        + (m_adoptExistingLogger ? "adoptExistingLogger" : "defaults")
        + String.format("%n  mode                 = %s", m_mode)
        + String.format("%n  wpilogFolder         = %s", m_wpilogFolder)
        + String.format("%n  fallbackFolder       = %s", m_fallbackFolder)
        + String.format("%n  compress             = %s", m_compress)
        + String.format("%n  ntPublish            = %s", m_ntPublish)
        + String.format("%n  minimumTier          = %s", m_minimumTier)
        + String.format("%n  perCycleByteBudget   = %.0f bytes", m_perCycleByteBudget)
        + String.format("%n  captureConsole       = %s", m_captureConsole)
        + String.format("%n  captureDriverStation = %s", m_captureDriverStation)
        + String.format("%n  ctreSignalLogger     = %s", m_ctreSignalLogger)
        + String.format("%n  urcl                 = %s", m_urcl)
        + String.format("%n  minFreeMegabytes     = %d MB", m_minFreeMegabytes)
        + String.format("%n  driverMirror         = %s", m_driverMirror)
        + ")";
  }

  @Override
  public String toString() {
    return describe();
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj) {
      return true;
    }
    if (!(obj instanceof LogConfig other)) {
      return false;
    }
    return m_adoptExistingLogger == other.m_adoptExistingLogger
        && m_mode == other.m_mode
        && m_wpilogFolder.equals(other.m_wpilogFolder)
        && m_fallbackFolder.equals(other.m_fallbackFolder)
        && m_compress == other.m_compress
        && m_ntPublish == other.m_ntPublish
        && m_minimumTier == other.m_minimumTier
        && Double.compare(m_perCycleByteBudget, other.m_perCycleByteBudget) == 0
        && m_captureConsole == other.m_captureConsole
        && m_captureDriverStation == other.m_captureDriverStation
        && m_ctreSignalLogger == other.m_ctreSignalLogger
        && m_urcl == other.m_urcl
        && m_minFreeMegabytes == other.m_minFreeMegabytes
        && m_driverMirror == other.m_driverMirror;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        m_adoptExistingLogger,
        m_mode,
        m_wpilogFolder,
        m_fallbackFolder,
        m_compress,
        m_ntPublish,
        m_minimumTier,
        m_perCycleByteBudget,
        m_captureConsole,
        m_captureDriverStation,
        m_ctreSignalLogger,
        m_urcl,
        m_minFreeMegabytes,
        m_driverMirror);
  }

  /** One private copy constructor keeps the adopt flag out of every wither's argument list. */
  private LogConfig copy(
      RobotMode mode,
      String wpilogFolder,
      String fallbackFolder,
      boolean compress,
      NtPolicy ntPublish,
      Tier minimumTier,
      double perCycleByteBudget,
      boolean captureConsole,
      boolean captureDriverStation,
      boolean ctreSignalLogger,
      boolean urcl,
      int minFreeMegabytes,
      boolean driverMirror) {
    return new LogConfig(
        m_adoptExistingLogger,
        mode,
        wpilogFolder,
        fallbackFolder,
        compress,
        ntPublish,
        minimumTier,
        perCycleByteBudget,
        captureConsole,
        captureDriverStation,
        ctreSignalLogger,
        urcl,
        minFreeMegabytes,
        driverMirror);
  }

  private static String requireFolder(String folder, String method) {
    if (folder == null || folder.isBlank()) {
      throw new IllegalArgumentException(
          "LogConfig."
              + method
              + ": folder was "
              + (folder == null ? "null" : "blank")
              + "; it must be a non-blank path. "
              + "Fix: pass something like \"/U/logs\" (the USB stick) or \"/home/lvuser/logs\".");
    }
    return folder;
  }
}

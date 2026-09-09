package org.rootstock.core;

import edu.wpi.first.wpilibj2.command.CommandScheduler;
import org.littletonrobotics.junction.LoggedRobot;
import org.rootstock.core.compat.Clock;
import org.rootstock.core.spi.LogConfig;

/**
 * The convenience base class, and the only one Rootstock ships. Every line of it is a delegation to
 * the public {@link RootstockLifecycle}.
 *
 * <pre>{@code
 * public class Robot extends RootstockRobot {
 *   private final RobotContainer m_container;
 *
 *   public Robot() {
 *     super(LogConfig.defaults()
 *               .withWpilogFolder("/U/logs")
 *               .withCtreSignalLogger(true));
 *
 *     m_container = new RobotContainer();   // registers via RootstockRegistry.addAll(...)
 *
 *     lifecycle().init();                   // LAST, so the boot dump reflects what you registered
 *   }
 * }
 * }</pre>
 *
 * <p><b>Revision 2 had two of these</b> — {@code RootstockRobot extends TimedRobot} in core and
 * {@code RootstockLoggedRobot extends LoggedRobot} in a separate artifact, kept in sync by hand and by
 * a test. Maintainer decision 3 makes AdvantageKit a required dependency, so the {@code TimedRobot}
 * variant has no consumer and the split has no purpose. {@code RootstockLoggedRobot} does not exist.
 *
 * <p><b>A team that does not want this class does not need it.</b> That is the point of D29 and it
 * survives decision 3 completely intact: {@link RootstockLifecycle} is public, and the partial-adoption
 * shape in its javadoc keeps the team's own base class untouched.
 *
 * <p><b>There is no {@code robotInit()} override here, and there must not be.</b> D13a is explicit:
 * this class overrides {@code robotPeriodic()}, {@code disabledInit()} and {@code close()} only, and
 * all initialisation is constructor work. ArchUnit rule 5 bans the identifier from the library's
 * surface, so it needs no exception. The mechanism that replaces it is {@link RootstockLifecycle#init()}'s
 * idempotence: a subclass calls {@code lifecycle().init()} at the end of its own constructor — the
 * recommended form — and if it does not, the first {@link #robotPeriodic()} runs it, which is after
 * the subclass field initialisers have run, so the registry it validates is populated. A team's
 * <i>own</i> {@code Robot} may still override {@code robotInit()}; rule 5 governs library code.
 */
public class RootstockRobot extends LoggedRobot {

  private final RootstockLifecycle m_lifecycle;

  /**
   * Defaults. Exactly equivalent to {@code RootstockRobot(LogConfig.defaults())}, which means
   * Rootstock configures <b>and starts</b> AdvantageKit's {@code Logger}.
   *
   * <p>If your own code has already called {@code Logger.start()}, this constructor will start it a
   * second time and that is a crash at boot — use {@code super(LogConfig.adoptExistingLogger())},
   * or keep your own base class and drive {@link RootstockLifecycle} by hand.
   */
  protected RootstockRobot() {
    this(LogConfig.defaults());
  }

  /**
   * The configurable form. <b>The argument is an immutable value, not a {@code Consumer} and not a
   * builder</b> (D13a): a consumer implies a mutable config object, which the library forbids
   * everywhere else, and {@code LogConfig.defaults().withWpilogFolder(..).withCompress(true)} reads
   * the same and is a value.
   *
   * <p>Both constructors are {@code protected}: this is a base class, and the only legal caller of
   * either is a subclass {@code super(...)} call.
   *
   * @param config the logging configuration; {@link LogConfig#defaults()} starts the Logger,
   *     {@link LogConfig#adoptExistingLogger()} attaches to one your code already started
   */
  protected RootstockRobot(LogConfig config) {
    // The loop period is captured ONCE, here, from IterativeRobotBase.getPeriod(). Everything in the
    // library that converts a Time or a Frequency to a cycle count reads Clock.dt(), so a robot
    // running at 100 Hz gets correct debounce and rate windows without a second place to configure
    // it — and nothing in Rootstock reads a raw FPGA timestamp or builds a Timer (rule 3).
    Clock.setPeriodSeconds(getPeriod());
    m_lifecycle = RootstockLifecycle.create(config);
  }

  /**
   * The whole loop: Rootstock before, the scheduler, Rootstock after.
   *
   * <p>The first call also runs {@link RootstockLifecycle#init()} if the subclass constructor did not.
   */
  @Override
  public void robotPeriodic() {
    m_lifecycle.beforeUserPeriodic();
    CommandScheduler.getInstance().run();
    m_lifecycle.afterUserPeriodic();
  }

  /** Runs the verified full-config re-apply and everything else that belongs on a disable edge. */
  @Override
  public void disabledInit() {
    m_lifecycle.disabledInit();
  }

  /** Closes the lifecycle, then the base class. */
  @Override
  public void close() {
    m_lifecycle.close();
    super.close();
  }

  /**
   * The lifecycle this robot drives.
   *
   * <p>Exposed so a subclass can call {@code init()} explicitly at the end of its constructor — the
   * recommended form — or reorder the loop, without giving up the base class.
   *
   * @return the lifecycle; never null
   */
  protected final RootstockLifecycle lifecycle() {
    return m_lifecycle;
  }
}

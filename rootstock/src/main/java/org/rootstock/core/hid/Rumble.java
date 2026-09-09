package org.rootstock.core.hid;

import edu.wpi.first.wpilibj.GenericHID;
import edu.wpi.first.wpilibj.GenericHID.RumbleType;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;

/**
 * The one-line way to play a haptic pattern on a controller.
 *
 * <p>WPILib gives you exactly {@code setRumble(RumbleType, double)}: no getter, no duration, no
 * patterns, no arbitration and no auto-clear on disable. This builder turns "buzz the driver" into a
 * {@link Command} with a defined length and a priority, and routes it through
 * {@link RumbleScheduler} so two subsystems cannot stomp each other.
 *
 * <pre>{@code
 * m_arm.hasGamePiece().onTrue(
 *     Rumble.on(m_driver.getHID())
 *         .side(RumbleType.kLeftRumble)
 *         .priority(50)
 *         .play(RumblePattern.doubleTap()));
 * }</pre>
 *
 * <p>The returned command requires no subsystem, so it never interrupts anything and nothing
 * interrupts it. Overlap is expected and is exactly what the scheduler's priorities are for: the
 * driver feels the highest-priority fact per motor, and the losing pattern keeps running underneath
 * so it reappears when the winner ends.
 *
 * <p>Left and right are independent, so two facts can be encoded at once - "game piece acquired" on
 * the left, "aligned to target" on the right.
 */
public final class Rumble {

  private Rumble() {
    throw new AssertionError("Rumble is a static factory; use Rumble.on(hid).");
  }

  /**
   * Starts building a rumble command for one controller.
   *
   * <p>The controller is registered with {@link RumbleScheduler} immediately, so it is zeroed on
   * disable even if the command is never scheduled.
   *
   * @param hid the controller, e.g. {@code m_driver.getHID()}.
   * @return a builder defaulting to both motors at priority 0.
   * @throws NullPointerException if {@code hid} is {@code null}.
   */
  public static Builder on(GenericHID hid) {
    if (hid == null) {
      throw new NullPointerException(
          "Rumble.on(null). Fix: pass the raw HID, e.g. Rumble.on(m_driver.getHID()) - "
              + "CommandXboxController itself is the command-based wrapper, getHID() is the "
              + "device.");
    }
    RumbleScheduler.register(hid);
    return new Builder(hid);
  }

  /** Chooses the motor and the priority, then produces the command. */
  public static final class Builder {

    private final GenericHID m_hid;
    private RumbleType m_side = RumbleType.kBothRumble;
    private int m_priority;

    private Builder(GenericHID hid) {
      m_hid = hid;
    }

    /**
     * Which motor to drive.
     *
     * @param t {@code kLeftRumble}, {@code kRightRumble} or {@code kBothRumble}. Left and right
     *     arbitrate independently; {@code kBothRumble} competes for both.
     * @return this builder.
     * @throws NullPointerException if {@code t} is {@code null}.
     */
    public Builder side(RumbleType t) {
      if (t == null) {
        throw new NullPointerException(
            "Rumble.side(null). Expected kLeftRumble, kRightRumble or kBothRumble. "
                + "Fix: import edu.wpi.first.wpilibj.GenericHID.RumbleType and pass one of them.");
      }
      m_side = t;
      return this;
    }

    /**
     * How important this rumble is. Highest active priority wins, per motor.
     *
     * <p>There is no fixed scale - pick one and write it down. A workable convention: 0 for ambient
     * feedback, 50 for "you have the game piece", 100 for "something is wrong". Ties go to whichever
     * request started most recently.
     *
     * @param p the priority; may be negative.
     * @return this builder.
     */
    public Builder priority(int p) {
      m_priority = p;
      return this;
    }

    /**
     * Builds the command that plays the pattern.
     *
     * <p>The command registers a request on start, releases it on end (including on interrupt), and
     * finishes on its own after the pattern's duration. It requires no subsystem and does not run
     * while disabled, which together with {@link RumbleScheduler#disabledInit()} is why a rumble can
     * never be left on.
     *
     * @param p the pattern to play.
     * @return the command.
     * @throws NullPointerException if {@code p} is {@code null}.
     */
    public Command play(RumblePattern p) {
      if (p == null) {
        throw new NullPointerException(
            "Rumble.play(null). Fix: pass a pattern, e.g. RumblePattern.doubleTap() or "
                + "RumblePattern.pulse(0.5, Seconds.of(0.25)).");
      }
      final GenericHID hid = m_hid;
      final RumbleType side = m_side;
      final int priority = m_priority;
      // One-element array because startEnd's Runnables cannot return the request id. The command is
      // never concurrently scheduled with itself (the WPILib scheduler refuses), so this is safe.
      final long[] id = {-1L};
      return Commands.startEnd(
              () -> id[0] = RumbleScheduler.begin(hid, side, priority, p),
              () -> RumbleScheduler.end(id[0]))
          .withTimeout(p.duration())
          .withName("Rumble/" + p.name() + "@p" + priority + "/port" + hid.getPort());
    }
  }
}

package org.pumpkinlib.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.pumpkinlib.core.config.CanIdRegistry;

/**
 * Every motor on one output shaft: a leader and the followers that turn with it.
 *
 * <p>The shape is deliberately not "a list of motors". A gearbox has exactly one motor whose sensor
 * is the mechanism's sensor and whose closed loop is the mechanism's closed loop; the rest exist to
 * add torque. Making that asymmetry structural means the question "which encoder do I read?" has
 * only one answer, and a two-motor mechanism cannot accidentally be configured with two competing
 * loops.
 *
 * <p>Direction is expressed here and only here. A {@code Reduction} is always positive, so
 * "the mechanism runs backwards" is fixed by flipping the leader's {@code inverted} flag — which
 * flips the whole group together, because each follower's direction is stored as a
 * <em>relative</em> {@link Follower} sense rather than as an absolute inversion of its own.
 *
 * <p>Authoring is a chain, and every link returns a new value:
 *
 * <pre>{@code
 * MotorGroup.leader(MotorSpec.talonFX(20, "rio").foc(true))
 *           .follower(MotorSpec.talonFX(21, "rio"), Follower.OPPOSED)
 * }</pre>
 *
 * @param leader the motor whose sensor and closed loop are the mechanism's
 * @param followers the motors that add torque, each with its direction relative to the leader
 */
public record MotorGroup(MotorSpec leader, List<MotorGroup.FollowerSpec> followers) {

  /**
   * A follower motor and which way it turns relative to the leader.
   *
   * @param spec the follower's hardware description
   * @param sense whether it turns with the leader or against it
   */
  public record FollowerSpec(MotorSpec spec, Follower sense) {

    /** Rejects the two null components, neither of which any factory can produce. */
    public FollowerSpec {
      spec = Objects.requireNonNull(spec, "MotorGroup.FollowerSpec: the follower spec is required.");
      sense =
          Objects.requireNonNull(
              sense,
              "MotorGroup.FollowerSpec: the follower sense is required. Pass Follower.ALIGNED if "
                  + "the follower turns the same way as the leader, Follower.OPPOSED if the two "
                  + "motors face each other across the gearbox.");
    }

    /**
     * One line naming the follower and what its sense means physically.
     *
     * @return a human-readable description
     */
    public String describe() {
      return spec.describe() + ", " + sense.describe();
    }
  }

  /** Defensive copy of the follower list; the record is a value and must stay one. */
  public MotorGroup {
    leader =
        Objects.requireNonNull(
            leader,
            "MotorGroup: a leader is required. Start with MotorGroup.leader(MotorSpec.talonFX(...))"
                + " — the leader is the motor whose sensor and closed loop are the mechanism's.");
    followers = List.copyOf(followers);
  }

  /**
   * A group of one: the leader, no followers.
   *
   * @param leader the motor whose sensor and closed loop are the mechanism's
   * @return the group
   */
  public static MotorGroup leader(MotorSpec leader) {
    return new MotorGroup(leader, List.of());
  }

  /**
   * A copy of this group with one more follower.
   *
   * @param spec the follower's hardware description
   * @param sense whether it turns with the leader or against it
   * @return a copy
   */
  public MotorGroup follower(MotorSpec spec, Follower sense) {
    List<FollowerSpec> next = new ArrayList<>(followers);
    next.add(new FollowerSpec(spec, sense));
    return new MotorGroup(leader, next);
  }

  /**
   * A copy of this group with one more follower turning the same way as the leader.
   *
   * <p>The two-argument form is the one the documentation uses, because on a mirrored gearbox
   * {@link Follower#OPPOSED} is the correct answer and a silent default of "aligned" is the failure
   * mode this library is trying to remove. This overload exists for the genuinely aligned case.
   *
   * @param spec the follower's hardware description
   * @return a copy
   */
  public MotorGroup follower(MotorSpec spec) {
    return follower(spec, Follower.ALIGNED);
  }

  /**
   * A copy with a different leader, keeping the followers.
   *
   * <p>Serves the practice-bot case: the same mechanism with a different CAN id or a different
   * vendor on the second robot.
   *
   * @param spec the new leader
   * @return a copy
   */
  public MotorGroup withLeader(MotorSpec spec) {
    return new MotorGroup(spec, followers);
  }

  /**
   * Whether the leader's output is inverted, which is what every backend writes to the device.
   *
   * @return the leader's inversion flag
   */
  public boolean leaderInverted() {
    return leader.inverted();
  }

  /**
   * How many motors are on this output shaft.
   *
   * <p>This is the multiplier on the simulation motor curve and on the stall force a mechanism can
   * produce, so it is derived from the group rather than typed anywhere.
   *
   * @return {@code 1 + followers.size()}
   */
  public int count() {
    return 1 + followers.size();
  }

  /**
   * The leader followed by every follower, in declaration order.
   *
   * @return an unmodifiable list of every spec in the group
   */
  public List<MotorSpec> allSpecs() {
    List<MotorSpec> out = new ArrayList<>(count());
    out.add(leader);
    for (FollowerSpec f : followers) {
      out.add(f.spec());
    }
    return List.copyOf(out);
  }

  /**
   * The motor model of the group, taken from the leader.
   *
   * <p>A mixed-model gearbox is a problem, not a feature, and {@link #problems()} says so.
   *
   * @return the leader's motor model
   */
  public MotorModel model() {
    return leader.model();
  }

  /**
   * Every CAN device this group declares, for the one global duplicate-ID scan.
   *
   * @param owner the mechanism that declares these motors
   * @return the devices; empty for a group of PWM or simulated motors
   */
  public List<CanIdRegistry.Device> canDevices(String owner) {
    List<CanIdRegistry.Device> out = new ArrayList<>(count());
    leader.canDevice(owner, "leader").ifPresent(out::add);
    for (int i = 0; i < followers.size(); i++) {
      followers.get(i).spec().canDevice(owner, "follower " + (i + 1)).ifPresent(out::add);
    }
    return List.copyOf(out);
  }

  /**
   * Every problem visible from this group alone.
   *
   * <p><b>Never throws, never returns null.</b> The checks are the ones that need nothing but the
   * group: a duplicate id inside the group (a copy-paste that would make two motors fight), a
   * follower on a different bus from its leader (which no vendor's follower mode supports), a mixed
   * vendor group, and a mixed motor model.
   *
   * @return the problems, in declaration order; empty when the group is fine
   */
  public List<String> problems() {
    List<String> out = new ArrayList<>(leader.problems());
    Set<String> seen = new HashSet<>();
    if (leader.isCanDevice()) {
      seen.add(leader.canBus() + "#" + leader.deviceId());
    }

    for (int i = 0; i < followers.size(); i++) {
      MotorSpec f = followers.get(i).spec();
      out.addAll(f.problems());

      if (f.isCanDevice()) {
        String key = f.canBus() + "#" + f.deviceId();
        if (!seen.add(key)) {
          out.add(
              "Motor group: device id "
                  + f.deviceId()
                  + " on bus \""
                  + f.canBus()
                  + "\" appears twice in the same group (leader "
                  + leader.name()
                  + ", follower "
                  + (i + 1)
                  + "). Two motors cannot share an id: one of them will never be commanded, and "
                  + "the other will be commanded twice. Fix: give the second motor its own id in "
                  + "Phoenix Tuner or the REV Hardware Client and update this config.");
        }
        if (leader.isCanDevice() && !f.canBus().equals(leader.canBus())) {
          out.add(
              "Motor group: follower "
                  + f.name()
                  + " is on bus \""
                  + f.canBus()
                  + "\" but the leader "
                  + leader.name()
                  + " is on bus \""
                  + leader.canBus()
                  + "\". No vendor's follower mode crosses a CAN bus. Fix: move both motors to the "
                  + "same bus, or drive them as two independent mechanisms.");
        }
      }

      if (!f.vendor().equals(leader.vendor())) {
        out.add(
            "Motor group: follower "
                + f.name()
                + " is a "
                + f.vendor()
                + " device but the leader "
                + leader.name()
                + " is a "
                + leader.vendor()
                + " device. Follower mode is a vendor feature and does not cross vendors. Fix: use "
                + "the same vendor for every motor on one gearbox.");
      } else if (f.model() != leader.model()) {
        out.add(
            "Motor group: follower "
                + f.name()
                + " is a "
                + f.model().displayName()
                + " but the leader is a "
                + leader.model().displayName()
                + ". Mixed motors on one gearbox make the simulated free speed, the stall force and "
                + "every derived gain wrong. Fix: use the same motor on both ends, or declare the "
                + "group with the motor you actually have.");
      }
    }
    return List.copyOf(out);
  }

  /**
   * The group as the boot dump prints it: the leader, then one indented line per follower.
   *
   * @return a multi-line, human-readable description
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(count()).append(count() == 1 ? " motor" : " motors").append(System.lineSeparator());
    sb.append("  leader   ").append(leader.describe());
    for (FollowerSpec f : followers) {
      sb.append(System.lineSeparator()).append("  follower ").append(f.describe());
    }
    return sb.toString();
  }
}

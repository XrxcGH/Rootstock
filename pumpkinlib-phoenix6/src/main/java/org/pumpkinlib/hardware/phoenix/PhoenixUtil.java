package org.pumpkinlib.hardware.phoenix;

import com.ctre.phoenix6.StatusCode;
import com.ctre.phoenix6.configs.CANcoderConfiguration;
import com.ctre.phoenix6.configs.CANrangeConfiguration;
import com.ctre.phoenix6.configs.CurrentLimitsConfigs;
import com.ctre.phoenix6.configs.MagnetSensorConfigs;
import com.ctre.phoenix6.configs.MotionMagicConfigs;
import com.ctre.phoenix6.configs.MotorOutputConfigs;
import com.ctre.phoenix6.configs.Pigeon2Configuration;
import com.ctre.phoenix6.configs.ProximityParamsConfigs;
import com.ctre.phoenix6.configs.Slot0Configs;
import com.ctre.phoenix6.configs.SoftwareLimitSwitchConfigs;
import com.ctre.phoenix6.configs.TalonFXConfiguration;
import com.ctre.phoenix6.configs.TalonFXSConfiguration;
import com.ctre.phoenix6.hardware.CANcoder;
import com.ctre.phoenix6.hardware.CANrange;
import com.ctre.phoenix6.hardware.ParentDevice;
import com.ctre.phoenix6.hardware.Pigeon2;
import com.ctre.phoenix6.hardware.TalonFX;
import com.ctre.phoenix6.hardware.TalonFXS;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.pumpkinlib.core.alert.Alerts;
import org.pumpkinlib.core.alert.MatchImpact;
import org.pumpkinlib.core.alert.PumpkinAlert;
import org.pumpkinlib.core.compat.Platform;

/**
 * The <b>only</b> path by which PumpkinLib writes a Phoenix 6 configuration, and the reason there
 * are exactly two of them.
 *
 * <h2>Why the config path splits in two, permanently</h2>
 *
 * <p>An earlier revision made the blocking, read-back-verified apply <em>"the only way CORE
 * configures hardware"</em> and then routed {@code MotorIO.applyGains} — documented "safe to call
 * every loop" and invoked from {@code periodic()} at 10 Hz while a student drags a tuning slider —
 * straight through it. {@code TalonFXConfigurator.apply(config)} blocks for a default 0.050 s, and
 * {@code refresh()} blocks too, so five verified rounds is <b>500 ms of blocked main loop, ten times
 * a second</b>. That is a guaranteed loop overrun shipped inside the library, on the exact workflow
 * the library exists to make pleasant.
 *
 * <p>So there are two calls and they are not interchangeable:
 *
 * <ul>
 *   <li>{@link #applyVerified(ParentDevice, Object, String)} — apply, read back, compare, retry.
 *       <b>Blocking.</b> Legal only off the hot path.
 *   <li>{@link #applyFast(TalonFX, Slot0Configs)} and friends — one frame queued with a
 *       <b>zero</b> timeout, no read-back, no retry, no blocking. The only config write allowed
 *       from an enabled {@code periodic()}.
 * </ul>
 *
 * <h2>Legal callers of {@link #applyVerified(ParentDevice, Object, String)}, exhaustively</h2>
 *
 * <ol>
 *   <li>a {@code MotorIO} constructor;
 *   <li>{@code MotorIO.reapplyFullConfigBlocking()};
 *   <li>{@code PumpkinRegistry.onDisable()};
 *   <li>self test;
 *   <li>{@code MotorIO.applyRaw(...)};
 *   <li>the {@code hasResetOccurred()} recovery path (a device that rebooted mid-match has lost its
 *       configuration; the alternative to a blocking re-apply is a dropped mechanism);
 *   <li>homing start and homing completion — homing deliberately suspends the device soft limits and
 *       reduces the stator limit while <em>enabled</em>, and a lost restore frame would leave the
 *       soft limits disabled for the rest of the enable period. It happens at most once per enable,
 *       under a voltage clamp, on a mechanism that is already crawling into a hard stop.
 * </ol>
 *
 * <p>It is <b>illegal</b> from any other enabled {@code periodic()}. That rule is enforced by an
 * ArchUnit class-name allowlist, not by this javadoc.
 *
 * <h2>Verification, and what a {@code false} return means</h2>
 *
 * <p>Phoenix's own {@code apply} already performs a device-side read/write check (it can return
 * {@link StatusCode#ConfigReadWriteMismatch}), so the extra round trip here is belt and braces
 * rather than the only line of defence. This class still does it, because the caller that matters
 * most — homing's soft-limit restore — must be able to distinguish "the device confirmed it" from
 * "the frame was sent". A {@code false} return means the configuration is <b>not</b> known to be on
 * the device, and a caller that cannot proceed safely without it (homing) must refuse to proceed.
 *
 * <p>Nothing here throws. A device that will not take a configuration raises a persistent alert
 * naming the device, the bus and the fix, and the robot keeps running.
 */
public final class PhoenixUtil {

  /** How many apply-and-verify rounds before giving up and raising the blocking alert. */
  public static final int kAttempts = 5;

  /**
   * The timeout the periodic path uses: zero, meaning "queue the frame and return".
   *
   * <p>Losing a single gain frame is harmless — the tunable re-pushes on the next change, and the
   * tuning UI re-pushes the whole set at 10 Hz while a value is being dragged. Blocking for an ack
   * inside a 20 ms loop is not harmless.
   */
  public static final double kFireAndForgetTimeoutSeconds = 0.0;

  /**
   * One alert per (owner, kind), reused.
   *
   * <p>Alerts are registered objects, so allocating a fresh one per failure would leak a new
   * dashboard row on every retry of every boot.
   */
  private static final Map<String, PumpkinAlert> kAlerts = new ConcurrentHashMap<>();

  private PhoenixUtil() {}

  // ================================================================================ CONSTRUCTION

  /**
   * Apply a configuration, read it back, compare, and retry up to {@link #kAttempts} times.
   *
   * <p><b>Blocking.</b> Each round costs one apply (default 0.050 s) plus one refresh. See the class
   * javadoc for the exhaustive list of legal callers; this is not one of the calls you may make from
   * an enabled loop.
   *
   * <p>In simulation the read-back is skipped: there is no CAN bus to be busy, the configurator
   * always succeeds, and five rounds of nothing is just five rounds of nothing.
   *
   * @param device the device to configure; null is a no-op returning false
   * @param config a Phoenix configuration object — a whole-device {@code *Configuration} or a single
   *     config group such as {@link Slot0Configs}; an unrecognised type is reported rather than
   *     silently dropped
   * @param owner the mechanism name that appears in the alert, for example {@code "Elevator"}
   * @return true if the configuration was applied <b>and</b> read back matching. Callers that must
   *     not proceed on failure (homing's soft-limit restore) check this; callers that only need best
   *     effort (construction, {@code disabledInit}) may ignore it and rely on the raised alert.
   */
  public static boolean applyVerified(ParentDevice device, Object config, String owner) {
    if (device == null || config == null) {
      return false;
    }
    String who = owner == null || owner.isBlank() ? describeDevice(device) : owner;

    if (Platform.isSimulation()) {
      applyOnce(device, config);
      return true;
    }

    boolean everApplied = false;
    for (int attempt = 1; attempt <= kAttempts; attempt++) {
      StatusCode applied = applyOnce(device, config);
      everApplied |= applied.isOK();
      if (applied.isOK() && readBackMatches(device, config)) {
        if (attempt > 1) {
          alert(
                  who,
                  who + "/config-retried",
                  who
                      + ": configuration applied on attempt "
                      + attempt
                      + " of "
                      + kAttempts
                      + " ("
                      + describeDevice(device)
                      + "). The CAN bus was busy at boot. Nothing is wrong yet, but if this "
                      + "number keeps climbing the bus is over-subscribed — check the signal rates "
                      + "printed by describe().",
                  MatchImpact.PIT_ONLY)
              .set(true);
        }
        return true;
      }
    }

    alert(
            who,
            who + "/config-apply-failed",
            who
                + ": configuration did NOT apply after "
                + kAttempts
                + " attempts ("
                + describeDevice(device)
                + "). "
                + (everApplied
                    ? "The device accepted the frame but did not read back matching, so it is "
                        + "running SOMETHING ELSE. "
                    : "The device never acknowledged the frame at all. ")
                + "The mechanism will run with whatever was previously on the device — which after a "
                + "power cycle is the factory default: no current limit, no soft limits, no gains. "
                + "Fix: check the CAN wiring and the device ID, then disable and re-enable to force "
                + "a verified re-apply.",
            MatchImpact.BLOCKS_MATCH)
        .set(true);
    return false;
  }

  // ==================================================================================== PERIODIC

  /**
   * Fire-and-forget slot apply with a zero timeout — the call queues the frame and returns without
   * waiting for the device ack.
   *
   * <p>This is what makes live gain tuning cost nothing. No read-back, no retry, no blocking.
   *
   * @param device the motor
   * @param slot the converted slot gains
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFX device, Slot0Configs slot) {
    return device.getConfigurator().apply(slot, kFireAndForgetTimeoutSeconds);
  }

  /**
   * Fire-and-forget Motion Magic apply with a zero timeout.
   *
   * @param device the motor
   * @param motionMagic the converted profile constraints
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFX device, MotionMagicConfigs motionMagic) {
    return device.getConfigurator().apply(motionMagic, kFireAndForgetTimeoutSeconds);
  }

  /**
   * Fire-and-forget motor-output apply with a zero timeout.
   *
   * <p>Exists because a pit crew's "coast the arm so I can move it" button toggles the neutral mode
   * from a command, and a blocking write from a button press is still a blocking write.
   *
   * @param device the motor
   * @param output the motor-output config, carrying inversion and neutral mode
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFX device, MotorOutputConfigs output) {
    return device.getConfigurator().apply(output, kFireAndForgetTimeoutSeconds);
  }

  /**
   * Fire-and-forget slot apply with a zero timeout, for a TalonFXS.
   *
   * @param device the motor
   * @param slot the converted slot gains
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFXS device, Slot0Configs slot) {
    return device.getConfigurator().apply(slot, kFireAndForgetTimeoutSeconds);
  }

  /**
   * Fire-and-forget Motion Magic apply with a zero timeout, for a TalonFXS.
   *
   * @param device the motor
   * @param motionMagic the converted profile constraints
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFXS device, MotionMagicConfigs motionMagic) {
    return device.getConfigurator().apply(motionMagic, kFireAndForgetTimeoutSeconds);
  }

  /**
   * Fire-and-forget motor-output apply with a zero timeout, for a TalonFXS.
   *
   * @param device the motor
   * @param output the motor-output config, carrying inversion and neutral mode
   * @return the status code the queue attempt returned
   */
  public static StatusCode applyFast(TalonFXS device, MotorOutputConfigs output) {
    return device.getConfigurator().apply(output, kFireAndForgetTimeoutSeconds);
  }

  // ====================================================================================== SHARED

  /**
   * The device as an alert names it: type, id and bus.
   *
   * @param device the device; null yields a placeholder rather than a crash
   * @return for example {@code "TalonFX 20 on bus \"rio\""}
   */
  public static String describeDevice(ParentDevice device) {
    if (device == null) {
      return "an unnamed Phoenix device";
    }
    return device.getClass().getSimpleName()
        + " "
        + device.getDeviceID()
        + " on bus \""
        + busName(device)
        + "\"";
  }

  /**
   * The CAN bus a device lives on.
   *
   * @param device the device
   * @return the bus name, or {@code "?"} when the device is null
   */
  public static String busName(ParentDevice device) {
    return device == null ? "?" : device.getNetwork().getName();
  }

  /**
   * A named CAN bus, as the non-deprecated device constructors want it.
   *
   * <p>{@code TalonFX(int, String)} and its siblings are deprecated for removal in Phoenix 6 26.x,
   * so every device this artifact opens goes through the {@link com.ctre.phoenix6.CANBus} overload.
   * Building the bus object here rather than at each call site also means the "blank means the
   * roboRIO's own bus" rule is written once.
   *
   * @param name the bus name; null or blank means the roboRIO's own bus
   * @return the bus
   */
  public static com.ctre.phoenix6.CANBus bus(String name) {
    return name == null || name.isBlank()
        ? com.ctre.phoenix6.CANBus.roboRIO()
        : new com.ctre.phoenix6.CANBus(name);
  }

  /**
   * Whether a bus is a CANivore, which is what {@code DynamicMotionMagicVoltage} requires alongside
   * a Phoenix Pro licence.
   *
   * <p>Detected through CAN FD rather than by string-matching {@code "rio"}: SystemCore has several
   * buses and a name test would be wrong the first season a team renamed theirs.
   *
   * @param device the device
   * @return true when the device's bus reports CAN FD
   */
  public static boolean isCanFd(ParentDevice device) {
    return device != null && device.getNetwork().isNetworkFD();
  }

  // ===================================================================================== private

  private static PumpkinAlert alert(String owner, String key, String text, MatchImpact impact) {
    return kAlerts.computeIfAbsent(
        key,
        k ->
            impact == MatchImpact.BLOCKS_MATCH
                ? Alerts.error(owner, text, impact)
                : Alerts.warning(owner, text, impact));
  }

  /**
   * One apply, dispatched on the concrete device and configuration types.
   *
   * <p>The dispatch is an explicit chain rather than reflection so that adding a device type is a
   * compile-time edit here, in one place, instead of a runtime surprise at an event.
   */
  private static StatusCode applyOnce(ParentDevice device, Object config) {
    if (device instanceof TalonFX fx) {
      if (config instanceof TalonFXConfiguration c) {
        return fx.getConfigurator().apply(c);
      }
      if (config instanceof Slot0Configs c) {
        return fx.getConfigurator().apply(c);
      }
      if (config instanceof MotionMagicConfigs c) {
        return fx.getConfigurator().apply(c);
      }
      if (config instanceof CurrentLimitsConfigs c) {
        return fx.getConfigurator().apply(c);
      }
      if (config instanceof SoftwareLimitSwitchConfigs c) {
        return fx.getConfigurator().apply(c);
      }
      if (config instanceof MotorOutputConfigs c) {
        return fx.getConfigurator().apply(c);
      }
    } else if (device instanceof TalonFXS fxs) {
      if (config instanceof TalonFXSConfiguration c) {
        return fxs.getConfigurator().apply(c);
      }
      if (config instanceof Slot0Configs c) {
        return fxs.getConfigurator().apply(c);
      }
      if (config instanceof MotionMagicConfigs c) {
        return fxs.getConfigurator().apply(c);
      }
      if (config instanceof CurrentLimitsConfigs c) {
        return fxs.getConfigurator().apply(c);
      }
      if (config instanceof SoftwareLimitSwitchConfigs c) {
        return fxs.getConfigurator().apply(c);
      }
      if (config instanceof MotorOutputConfigs c) {
        return fxs.getConfigurator().apply(c);
      }
    } else if (device instanceof CANcoder cancoder) {
      if (config instanceof CANcoderConfiguration c) {
        return cancoder.getConfigurator().apply(c);
      }
      if (config instanceof MagnetSensorConfigs c) {
        return cancoder.getConfigurator().apply(c);
      }
    } else if (device instanceof Pigeon2 pigeon) {
      if (config instanceof Pigeon2Configuration c) {
        return pigeon.getConfigurator().apply(c);
      }
    } else if (device instanceof CANrange range) {
      if (config instanceof CANrangeConfiguration c) {
        return range.getConfigurator().apply(c);
      }
      if (config instanceof ProximityParamsConfigs c) {
        return range.getConfigurator().apply(c);
      }
    }
    return StatusCode.FeatureNotSupported;
  }

  /**
   * Read the configuration back off the device and compare it, field for field, with what was sent.
   *
   * <p>The comparison is on Phoenix's own {@code serialize()} form, which is the exact
   * key-value string the device round-trips, so it cannot drift from the fields this library
   * happens to know about today.
   *
   * @return true when the read-back matched; also true when the pair is not verifiable, in which
   *     case an OK apply is all the confirmation that exists and pretending otherwise would fail
   *     every boot
   */
  private static boolean readBackMatches(ParentDevice device, Object config) {
    Object fresh = freshLike(config);
    if (fresh == null) {
      return true;
    }
    StatusCode refreshed = refreshInto(device, fresh);
    if (!refreshed.isOK()) {
      return false;
    }
    return serialize(fresh).equals(serialize(config));
  }

  private static StatusCode refreshInto(ParentDevice device, Object config) {
    if (device instanceof TalonFX fx) {
      if (config instanceof TalonFXConfiguration c) {
        return fx.getConfigurator().refresh(c);
      }
      if (config instanceof Slot0Configs c) {
        return fx.getConfigurator().refresh(c);
      }
      if (config instanceof MotionMagicConfigs c) {
        return fx.getConfigurator().refresh(c);
      }
      if (config instanceof CurrentLimitsConfigs c) {
        return fx.getConfigurator().refresh(c);
      }
      if (config instanceof SoftwareLimitSwitchConfigs c) {
        return fx.getConfigurator().refresh(c);
      }
      if (config instanceof MotorOutputConfigs c) {
        return fx.getConfigurator().refresh(c);
      }
    } else if (device instanceof TalonFXS fxs) {
      if (config instanceof TalonFXSConfiguration c) {
        return fxs.getConfigurator().refresh(c);
      }
      if (config instanceof Slot0Configs c) {
        return fxs.getConfigurator().refresh(c);
      }
      if (config instanceof MotionMagicConfigs c) {
        return fxs.getConfigurator().refresh(c);
      }
      if (config instanceof CurrentLimitsConfigs c) {
        return fxs.getConfigurator().refresh(c);
      }
      if (config instanceof SoftwareLimitSwitchConfigs c) {
        return fxs.getConfigurator().refresh(c);
      }
      if (config instanceof MotorOutputConfigs c) {
        return fxs.getConfigurator().refresh(c);
      }
    } else if (device instanceof CANcoder cancoder) {
      if (config instanceof CANcoderConfiguration c) {
        return cancoder.getConfigurator().refresh(c);
      }
      if (config instanceof MagnetSensorConfigs c) {
        return cancoder.getConfigurator().refresh(c);
      }
    } else if (device instanceof Pigeon2 pigeon) {
      if (config instanceof Pigeon2Configuration c) {
        return pigeon.getConfigurator().refresh(c);
      }
    } else if (device instanceof CANrange range) {
      if (config instanceof CANrangeConfiguration c) {
        return range.getConfigurator().refresh(c);
      }
      if (config instanceof ProximityParamsConfigs c) {
        return range.getConfigurator().refresh(c);
      }
    }
    return StatusCode.FeatureNotSupported;
  }

  /** An empty instance of the same configuration type, to refresh the device's own values into. */
  private static Object freshLike(Object config) {
    if (config instanceof TalonFXConfiguration) {
      return new TalonFXConfiguration();
    }
    if (config instanceof TalonFXSConfiguration) {
      return new TalonFXSConfiguration();
    }
    if (config instanceof CANcoderConfiguration) {
      return new CANcoderConfiguration();
    }
    if (config instanceof Pigeon2Configuration) {
      return new Pigeon2Configuration();
    }
    if (config instanceof CANrangeConfiguration) {
      return new CANrangeConfiguration();
    }
    if (config instanceof Slot0Configs) {
      return new Slot0Configs();
    }
    if (config instanceof MotionMagicConfigs) {
      return new MotionMagicConfigs();
    }
    if (config instanceof CurrentLimitsConfigs) {
      return new CurrentLimitsConfigs();
    }
    if (config instanceof SoftwareLimitSwitchConfigs) {
      return new SoftwareLimitSwitchConfigs();
    }
    if (config instanceof MotorOutputConfigs) {
      return new MotorOutputConfigs();
    }
    if (config instanceof MagnetSensorConfigs) {
      return new MagnetSensorConfigs();
    }
    if (config instanceof ProximityParamsConfigs) {
      return new ProximityParamsConfigs();
    }
    return null;
  }

  private static String serialize(Object config) {
    return config instanceof com.ctre.phoenix6.ISerializable s ? s.serialize() : String.valueOf(config);
  }
}

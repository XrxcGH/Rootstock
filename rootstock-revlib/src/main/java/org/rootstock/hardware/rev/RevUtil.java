package org.rootstock.hardware.rev;

import com.revrobotics.PersistMode;
import com.revrobotics.REVLibError;
import com.revrobotics.ResetMode;
import com.revrobotics.spark.SparkBase;
import com.revrobotics.spark.SparkFlex;
import com.revrobotics.spark.SparkLowLevel;
import com.revrobotics.spark.SparkMax;
import com.revrobotics.spark.config.SparkBaseConfig;
import com.revrobotics.spark.config.SparkFlexConfig;
import com.revrobotics.spark.config.SparkMaxConfig;
import java.util.Locale;
import org.rootstock.config.MotorSpec;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.match.MatchContext;

/**
 * The two — and only two — ways Rootstock writes configuration to a SPARK.
 *
 * <p>This is the REVLib half of design §3.9's split. {@link #applyVerified} is blocking, retried,
 * and legal only from a constructor, a device-reset recovery, {@code reapplyFullConfigBlocking()},
 * {@code onDisable()}, self-test, and homing start/completion. {@link #applyFast} is
 * fire-and-forget and is the only config write allowed on the periodic path, because live gain
 * tuning drags a slider at 10 Hz and a blocking config write ten times a second is a guaranteed
 * loop overrun.
 *
 * <h2>Three REVLib 2026.0.5 facts that shape this class, all verified with {@code javap} and the
 * shipped sources</h2>
 *
 * <ol>
 *   <li><b>{@code SparkBase.configure(...)} THROWS.</b> Verified in the 2026.0.5 source: on any
 *       error other than {@code kTimeout} and {@code kCannotPersistParametersWhileEnabled} it
 *       raises {@code IllegalStateException}. A vendordep that kills the robot program because a
 *       CAN ID was typed wrong is not acceptable behaviour for a library whose contract is
 *       "degrade, never crash", so every call in this artifact goes through here and every one is
 *       wrapped. Nothing in {@code org.rootstock.hardware.rev} lets a REVLib throw escape.
 *   <li><b>Persisting while enabled is refused by the device.</b> {@code
 *       REVLibError.kCannotPersistParametersWhileEnabled} exists for exactly this. So the persist
 *       decision is made here, from {@link MatchContext#isEnabled()}, and never by the caller: a
 *       verified apply persists when the robot is disabled (boot, {@code disabledInit},
 *       self-test — where it is both legal and valuable, because it survives a brownout) and does
 *       not persist when it is enabled (device-reset recovery mid-match, homing).
 *   <li><b>{@code configureAsync} is the real analogue of Phoenix's zero-timeout apply.</b>
 *       Verified: it "will immediately return {@code kOk} and the action will be done in the
 *       background". That is what {@link #applyFast} uses, with {@code kNoResetSafeParameters} and
 *       {@code kNoPersistParameters} — persisting flash on every slider drag would burn the
 *       SPARK's non-volatile memory, and resetting safe parameters would blow away everything not
 *       in the partial config being pushed.
 * </ol>
 *
 * <h2>Why the verified path has no read-back</h2>
 *
 * <p>Phoenix's {@code applyVerified} applies, then refreshes, then compares, because {@code
 * apply()} can succeed at the CAN layer while the device rejects a value. REVLib's {@code
 * configure()} is already a synchronous round trip that returns a per-parameter error code — a
 * rejected parameter comes back as {@code kParamInvalidValue} rather than as a silent success — so
 * the read-back is the vendor's, not ours. What we add is the retry (a busy bus at boot) and the
 * refusal to throw. <b>[UNVERIFIED-BY-EXECUTION]</b>: that {@code configure()} reports a rejected
 * individual parameter rather than only a transport failure is REVLib's documented return
 * contract, not something this library has observed on hardware.
 */
public final class RevUtil {

  /** How many times a verified apply retries before it gives up and raises an alert. */
  public static final int kAttempts = 5;

  private RevUtil() {}

  // ---- construction ----------------------------------------------------------------------------

  /**
   * Build the live controller a spec names.
   *
   * <p>This is the one place in the library that decides SPARK MAX versus SPARK FLEX, and the one
   * place that turns {@link org.rootstock.config.SparkModel} into REVLib's {@code MotorType}.
   * Getting the second one wrong drives a brushless motor as brushed, which makes a noise the whole
   * shop hears and does not turn — so it is derived from the enum constant the student picked off
   * the sticker, never guessed.
   *
   * @param spec the declared SPARK
   * @return a live {@code SparkMax} or {@code SparkFlex}; never null
   */
  public static SparkBase newSpark(MotorSpec.SparkSpec spec) {
    SparkLowLevel.MotorType type =
        spec.sparkModel().isBrushless()
            ? SparkLowLevel.MotorType.kBrushless
            : SparkLowLevel.MotorType.kBrushed;
    return spec.sparkModel().isFlex()
        ? new SparkFlex(spec.deviceId(), type)
        : new SparkMax(spec.deviceId(), type);
  }

  /**
   * An empty config object of the matching concrete type.
   *
   * <p>{@code SparkMaxConfig} and {@code SparkFlexConfig} are not interchangeable — each carries a
   * different secondary-encoder group ({@code alternateEncoder} versus {@code externalEncoder}) —
   * so the choice is made from the same enum constant that chose the controller class.
   *
   * @param spec the declared SPARK
   * @return a fresh, empty config; never null
   */
  public static SparkBaseConfig newConfig(MotorSpec.SparkSpec spec) {
    return spec.sparkModel().isFlex() ? new SparkFlexConfig() : new SparkMaxConfig();
  }

  // ---- construction / recovery path (BLOCKING) --------------------------------------------------

  /**
   * Apply a whole configuration, blocking, with retries, and never throwing.
   *
   * <p><b>Legal callers, exhaustively</b> (design §3.9): a {@code MotorIO} constructor; {@code
   * MotorIO.reapplyFullConfigBlocking()}; the device-reset recovery path; {@code
   * RootstockRegistry.onDisable()}; self-test; homing start and homing completion. It is illegal from
   * an enabled {@code periodic()} that is not one of those.
   *
   * <p>{@code kResetSafeParameters} is deliberate and is the whole reason a SPARK config is
   * declarative in this library: it means "this config, and factory defaults for everything else",
   * so a motor swapped in the pit behaves identically to the one that came out without anybody
   * remembering which knob was turned last season.
   *
   * <p>The persist decision is taken here — see the class javadoc, fact 2.
   *
   * @param device the SPARK to configure
   * @param config the whole configuration to push
   * @param owner the mechanism name, for the alert group and text
   * @return true if the device accepted the configuration; false after {@link #kAttempts} failures,
   *     in which case a {@link MatchImpact#BLOCKS_MATCH} alert has already been raised and the
   *     device is running whatever it had before
   */
  public static boolean applyVerified(SparkBase device, SparkBaseConfig config, String owner) {
    if (device == null || config == null) {
      return false;
    }
    // Persisting is only legal while disabled, and only worth its ~200 ms while disabled anyway.
    PersistMode persist =
        MatchContext.isEnabled() ? PersistMode.kNoPersistParameters : PersistMode.kPersistParameters;

    REVLibError last = REVLibError.kUnknown;
    for (int attempt = 1; attempt <= kAttempts; attempt++) {
      last = configureCatching(device, config, ResetMode.kResetSafeParameters, persist);
      if (last == REVLibError.kOk) {
        if (attempt > 1) {
          Alerts.warning(
                  owner,
                  owner
                      + ": SPARK config applied on attempt "
                      + attempt
                      + " of "
                      + kAttempts
                      + " (the CAN bus was busy). Nothing is wrong yet; if this shows up every"
                      + " boot, something is saturating the bus.",
                  MatchImpact.PIT_ONLY)
              .set(true);
        }
        return true;
      }
      if (last == REVLibError.kCannotPersistParametersWhileEnabled) {
        // The robot was enabled between the check above and the write. Drop the persist and retry
        // rather than failing: the configuration matters, the flash write does not.
        persist = PersistMode.kNoPersistParameters;
      }
    }

    Alerts.error(
            owner,
            owner
                + ": SPARK "
                + deviceIdOf(device)
                + " did NOT accept its configuration after "
                + kAttempts
                + " attempts (last error "
                + name(last)
                + "). The mechanism will run with WHATEVER was previously stored on the"
                + " controller, possibly last season's gear ratio. Fix: check the CAN wiring and"
                + " that the device ID matches the sticker, then re-deploy.",
            MatchImpact.BLOCKS_MATCH)
        .set(true);
    return false;
  }

  // ---- periodic path (NON-BLOCKING) -------------------------------------------------------------

  /**
   * Queue a partial configuration and return immediately.
   *
   * <p>This is the only config write allowed from {@code periodic()}, and it is what makes live
   * gain tuning cost nothing. Losing a frame is harmless: {@code TunableGains} re-pushes the whole
   * set at 10 Hz while a value is being dragged.
   *
   * <p>{@code kNoResetSafeParameters} is not optional here. {@code kResetSafeParameters} would
   * reset every parameter <em>not</em> present in the partial config to its factory default, so a
   * gain-only push would silently wipe the conversion factors, the current limit and the soft
   * limits. {@code kNoPersistParameters} is not optional either: the SPARK's flash has a finite
   * erase count and a slider drag is thousands of writes.
   *
   * @param device the SPARK to configure
   * @param config the partial configuration — only the fields actually set are sent
   * @param owner the mechanism name, for the alert group
   * @return {@code kOk} when the frame was queued; the failure code otherwise. Never throws.
   */
  public static REVLibError applyFast(SparkBase device, SparkBaseConfig config, String owner) {
    if (device == null || config == null) {
      return REVLibError.kInvalid;
    }
    REVLibError status = configureAsyncCatching(device, config);
    if (status != REVLibError.kOk) {
      Alerts.warning(
              owner,
              owner
                  + ": SPARK "
                  + deviceIdOf(device)
                  + " refused a live configuration update ("
                  + name(status)
                  + "). Tuning values are not reaching the controller; the mechanism is still"
                  + " running its last accepted gains. Fix: check the CAN link to that device.",
              MatchImpact.PIT_ONLY)
          .set(true);
    }
    return status;
  }

  // ---- health ----------------------------------------------------------------------------------

  /**
   * Whether a SPARK is actually answering.
   *
   * <p>Every {@code *IONeo} in the surveyed robot template hardcodes {@code inputs.connected =
   * true} with the comment "SPARK MAX has no cheap connection signal", so a disconnected controller
   * reports healthy for the whole match. It does have one: a device that is not on the bus reports
   * firmware version {@code 0}, because the firmware string is read from the device rather than
   * cached in the vendordep. Combined with the active-fault bit that is a real answer to a real
   * question.
   *
   * <p>In simulation there is no bus, so this reports connected — the alternative is every
   * simulated mechanism raising a disconnection alert on every boot.
   *
   * @param device the SPARK to ask
   * @return true when the device is answering and reporting no active fault
   */
  public static boolean connected(SparkBase device) {
    if (device == null) {
      return false;
    }
    if (Platform.isSimulation()) {
      return true;
    }
    return device.getFirmwareVersion() != 0 && !device.hasActiveFault();
  }

  /**
   * A short, human name for a REVLib error code.
   *
   * @param error the code; null becomes {@code "kUnknown"}
   * @return the enum constant name
   */
  public static String name(REVLibError error) {
    return error == null ? "kUnknown" : error.name();
  }

  /**
   * Every active fault and warning bit that is set, as one readable phrase.
   *
   * <p>For {@code describe()} and for the disconnection alert: "SPARK 9 reports faults [can,
   * sensor]" is actionable, {@code rawBits=0x24} is not.
   *
   * @param device the SPARK to ask
   * @return for example {@code "faults [can] warnings [brownout, hasReset]"}, or {@code "healthy"}
   */
  public static String describeHealth(SparkBase device) {
    if (device == null) {
      return "no device";
    }
    StringBuilder out = new StringBuilder();
    SparkBase.Faults faults = device.getFaults();
    SparkBase.Warnings warnings = device.getWarnings();
    append(out, faults.other, "fault:other");
    append(out, faults.motorType, "fault:motorType");
    append(out, faults.sensor, "fault:sensor");
    append(out, faults.can, "fault:can");
    append(out, faults.temperature, "fault:temperature");
    append(out, faults.gateDriver, "fault:gateDriver");
    append(out, faults.escEeprom, "fault:escEeprom");
    append(out, faults.firmware, "fault:firmware");
    append(out, warnings.brownout, "warn:brownout");
    append(out, warnings.overcurrent, "warn:overcurrent");
    append(out, warnings.stall, "warn:stall");
    append(out, warnings.sensor, "warn:sensor");
    append(out, warnings.hasReset, "warn:hasReset");
    return out.length() == 0 ? "healthy" : out.toString();
  }

  // ---- internals -------------------------------------------------------------------------------

  private static void append(StringBuilder out, boolean set, String label) {
    if (set) {
      if (out.length() > 0) {
        out.append(", ");
      }
      out.append(label);
    }
  }

  private static String deviceIdOf(SparkBase device) {
    return String.format(Locale.ROOT, "CAN id %d", device.getDeviceId());
  }

  /**
   * {@code configure()} with REVLib's {@code IllegalStateException} turned back into a code.
   *
   * <p>See class javadoc fact 1. The catch is {@code RuntimeException} rather than {@code
   * IllegalStateException} because the JNI layer underneath can surface other unchecked types and a
   * config write must not be able to take the robot program down whichever one arrives.
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static REVLibError configureCatching(
      SparkBase device, SparkBaseConfig config, ResetMode reset, PersistMode persist) {
    try {
      return device.configure(config, reset, persist);
    } catch (RuntimeException e) {
      return REVLibError.kError;
    }
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private static REVLibError configureAsyncCatching(SparkBase device, SparkBaseConfig config) {
    try {
      return device.configureAsync(
          config, ResetMode.kNoResetSafeParameters, PersistMode.kNoPersistParameters);
    } catch (RuntimeException e) {
      return REVLibError.kError;
    }
  }
}

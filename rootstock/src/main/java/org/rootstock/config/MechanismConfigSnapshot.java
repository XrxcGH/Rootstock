package org.rootstock.config;

import edu.wpi.first.wpilibj.util.WPILibVersion;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.rootstock.control.Gains;
import org.rootstock.core.Rootstock;
import org.rootstock.core.SafeMode;
import org.rootstock.core.config.DeployInfo;
import org.rootstock.units.MechanismUnits;
import org.rootstock.units.Range;

/**
 * The exact configuration the robot ran with, flattened to plain doubles and strings and written to
 * the log once at boot.
 *
 * <p>This is what makes "is it a bug in the library, or a regression between versions, or did
 * somebody edit a gear ratio?" answerable <b>from a log file alone</b>, which is the debuggability
 * bar the library holds itself to. Every derived number is here next to the number it was derived
 * from: the gear ratio and its written derivation, the travel per output rotation in both user
 * units and SI, the soft limits, all seven gains in volts-per-SI, the chosen control location
 * <i>and</i> whether the team chose it, and the list of signals actually subscribed — so a
 * {@code NaN} field in the log is self-explaining rather than mysterious.
 *
 * <h2>Why this is not {@code StructSerializable} — a verified deviation from {@code design/01} §5.7</h2>
 *
 * <p>The design declares this record {@code implements StructSerializable} with
 * {@code StructGenerator.genRecord(MechanismConfigSnapshot.class)}. That was written with the note
 * that struct support for the component types was <i>unverified</i>. It has now been verified
 * against WPILib 2026.2.2 and it does not work: {@code StructGenerator.genRecord} supports
 * primitives, enums and nested {@code StructSerializable} types only. A WPILib struct is fixed-size
 * by definition, so a {@code String} component cannot be encoded — {@code genRecord} prints
 * "Could not structify record component" to stderr for each of the fourteen string fields here and
 * returns a <b>no-op struct that silently logs nothing</b>. Publishing that would produce a config
 * topic that exists, appears healthy, and is empty, which is worse than not having one.
 *
 * <p>So this record stays a plain value and exposes {@link #fields()} and {@link #toStrings()}
 * instead. The telemetry layer publishes it field by field, which costs a few more log entries and
 * has the property that actually matters: every value is really in the file.
 *
 * @param name the mechanism's name, as the team wrote it
 * @param kind {@code POSITION}, {@code VELOCITY} or {@code SIMPLE}
 * @param backend the leader's vendor, for example {@code "CTRE Phoenix 6"}
 * @param controlLocation where the loop ran
 * @param controlLocationSource {@code EXPLICIT} or {@code DEFAULTED}
 * @param outputMode {@code VOLTAGE} or {@code TORQUE_CURRENT}
 * @param focEnabled whether field-oriented control was requested on the leader
 * @param leaderCanId the leader's CAN id, or -1 when it is not a CAN device
 * @param canBus the CAN bus name
 * @param followerCount how many followers the leader had
 * @param reductionRotorPerOutput rotor rotations per output rotation
 * @param reductionDerivation the gearbox as the team wrote it, for example {@code "58:10 x 58:18"}
 * @param userPerOutputRotation metres or degrees of travel per output rotation
 * @param siPerOutputRotation metres or radians per output rotation
 * @param unitLabel {@code "m"} or {@code "deg"}
 * @param siLabel {@code "m"} or {@code "rad"}
 * @param softMin the lower soft limit in user units, or NaN when there is none
 * @param softMax the upper soft limit in user units, or NaN when there is none
 * @param kP proportional gain, V/m or V/rad
 * @param kI integral gain
 * @param kD derivative gain, V/(m/s) or V/(rad/s)
 * @param kS static friction feedforward, volts
 * @param kV velocity feedforward, V/(m/s) or V/(rad/s)
 * @param kA acceleration feedforward, V/(m/s^2) or V/(rad/s^2)
 * @param kG gravity feedforward, volts
 * @param gravityMode {@code NONE}, {@code CONSTANT} or {@code COSINE}
 * @param horizontalReference the angle at which the mechanism is level, in user units
 * @param gravityArmPositionOffsetRot the negated horizontal reference in output rotations, which is
 *     the number Phoenix actually receives and silently clamps to (-0.25, 0.25)
 * @param maxVelocity the cruise velocity in user units per second
 * @param maxAcceleration the acceleration limit in user units per second squared
 * @param jerk the jerk limit in user units per second cubed, or zero for none
 * @param useExpo whether the profile was exponential
 * @param expoKvVoltsPerRps the device-facing {@code kV}, {@code gains.kV × siPerOutputRotation}
 * @param expoKaVoltsPerRps2 the device-facing {@code kA}
 * @param statorAmps the stator current limit
 * @param supplyAmps the supply current limit
 * @param feedbackKind the position sensor's type name
 * @param homingKind the homing strategy's type name
 * @param subscribedSignals which device signals were read, so a NaN field explains itself
 * @param simMassOrMoi the simulated mass in kilograms or moment of inertia in kg&middot;m&sup2;
 * @param simStartPosition where the simulated mechanism started, in SI
 * @param configErrorCount how many problems validation found
 * @param safeMode whether the robot booted into safe mode
 * @param rootstockVersion the library version
 * @param wpilibVersion the WPILib version
 * @param vendorLibVersion the vendor library version, when the backend can report one
 */
public record MechanismConfigSnapshot(
    String name,
    String kind,
    String backend,
    String controlLocation,
    String controlLocationSource,
    String outputMode,
    boolean focEnabled,
    int leaderCanId,
    String canBus,
    int followerCount,
    double reductionRotorPerOutput,
    String reductionDerivation,
    double userPerOutputRotation,
    double siPerOutputRotation,
    String unitLabel,
    String siLabel,
    double softMin,
    double softMax,
    double kP,
    double kI,
    double kD,
    double kS,
    double kV,
    double kA,
    double kG,
    String gravityMode,
    double horizontalReference,
    double gravityArmPositionOffsetRot,
    double maxVelocity,
    double maxAcceleration,
    double jerk,
    boolean useExpo,
    double expoKvVoltsPerRps,
    double expoKaVoltsPerRps2,
    double statorAmps,
    double supplyAmps,
    String feedbackKind,
    String homingKind,
    String subscribedSignals,
    double simMassOrMoi,
    double simStartPosition,
    int configErrorCount,
    boolean safeMode,
    String rootstockVersion,
    String wpilibVersion,
    String vendorLibVersion) {

  /** The log prefix every snapshot is published under. */
  public static final String kLogRoot = "/Rootstock/Config/";

  /** What a string field carries when the value is genuinely unknown. */
  public static final String kUnknown = DeployInfo.kUnknown;

  /** What a numeric field carries when it does not apply to this mechanism. */
  public static final double kNotApplicable = Double.NaN;

  /** Normalises the string components so a log entry never carries a bare {@code null}. */
  public MechanismConfigSnapshot {
    name = blankTo(name, "(unnamed mechanism)");
    kind = blankTo(kind, kUnknown);
    backend = blankTo(backend, kUnknown);
    controlLocation = blankTo(controlLocation, kUnknown);
    controlLocationSource = blankTo(controlLocationSource, kUnknown);
    outputMode = blankTo(outputMode, kUnknown);
    canBus = blankTo(canBus, "");
    reductionDerivation = blankTo(reductionDerivation, kUnknown);
    unitLabel = blankTo(unitLabel, "");
    siLabel = blankTo(siLabel, "");
    gravityMode = blankTo(gravityMode, kUnknown);
    feedbackKind = blankTo(feedbackKind, kUnknown);
    homingKind = blankTo(homingKind, "none");
    subscribedSignals = blankTo(subscribedSignals, kUnknown);
    rootstockVersion = blankTo(rootstockVersion, kUnknown);
    wpilibVersion = blankTo(wpilibVersion, kUnknown);
    vendorLibVersion = blankTo(vendorLibVersion, kUnknown);
  }

  // -------------------------------------------------------------------------------------------
  // Factories
  // -------------------------------------------------------------------------------------------

  /**
   * The snapshot of a position mechanism.
   *
   * @param config the config
   * @return the snapshot
   */
  public static MechanismConfigSnapshot of(PositionConfig config) {
    MechanismUnits units = config.units();
    Gains gains = config.control().gains();
    MotorSpec leader = config.motors().leader();
    Range travel = config.limits().range();
    double horizontal = config.axis().horizontalReference();

    return new MechanismConfigSnapshot(
        config.name(),
        config.kind().name(),
        leader.vendor(),
        config.control().location().name(),
        config.control().locationSource().name(),
        leader.outputMode().name(),
        leader.foc(),
        leader.deviceId(),
        leader.canBus(),
        config.motors().count() - 1,
        config.reduction().rotorPerOutput(),
        config.reduction().describe(),
        units.userPerOutputRotation(),
        units.siPerOutputRotation(),
        units.unitLabel(),
        units.siLabel(),
        travel.min(),
        travel.max(),
        gains.kP(),
        gains.kI(),
        gains.kD(),
        gains.kS(),
        gains.kV(),
        gains.kA(),
        gains.kG(),
        config.control().gravity().name(),
        horizontal,
        -horizontal / 360.0,
        config.control().constraints().maxVelocity(),
        config.control().constraints().maxAcceleration(),
        config.control().constraints().jerk(),
        config.control().useExpo(),
        gains.kV() * units.siPerOutputRotation(),
        gains.kA() * units.siPerOutputRotation(),
        config.limits().current().statorAmps(),
        config.limits().current().supplyAmps(),
        config.feedback().getClass().getSimpleName(),
        config.homing().getClass().getSimpleName(),
        signalsFor(MechanismKind.POSITION),
        config.sim().hasCarriageMass() ? config.sim().massKg() : config.sim().moiKgM2(),
        config.sim().startingPositionSi(),
        config.errors().size(),
        SafeMode.isActive(),
        Rootstock.VERSION,
        WPILibVersion.Version,
        kUnknown);
  }

  /**
   * The snapshot of a velocity mechanism.
   *
   * @param config the config
   * @return the snapshot
   */
  public static MechanismConfigSnapshot of(VelocityConfig config) {
    MechanismUnits units = config.units();
    Gains gains = config.control().gains();
    MotorSpec leader = config.motors().leader();

    return new MechanismConfigSnapshot(
        config.name(),
        config.kind().name(),
        leader.vendor(),
        config.control().location().name(),
        config.control().locationSource().name(),
        leader.outputMode().name(),
        leader.foc(),
        leader.deviceId(),
        leader.canBus(),
        config.motors().count() - 1,
        config.reduction().rotorPerOutput(),
        config.reduction().describe(),
        units.userPerOutputRotation(),
        units.siPerOutputRotation(),
        units.unitLabel(),
        units.siLabel(),
        kNotApplicable,
        kNotApplicable,
        gains.kP(),
        gains.kI(),
        gains.kD(),
        gains.kS(),
        gains.kV(),
        gains.kA(),
        gains.kG(),
        config.control().gravity().name(),
        kNotApplicable,
        kNotApplicable,
        config.control().constraints().maxVelocity(),
        config.control().constraints().maxAcceleration(),
        config.control().constraints().jerk(),
        config.control().useExpo(),
        gains.kV() * units.siPerOutputRotation(),
        gains.kA() * units.siPerOutputRotation(),
        config.current().statorAmps(),
        config.current().supplyAmps(),
        config.feedback().getClass().getSimpleName(),
        "none",
        signalsFor(MechanismKind.VELOCITY),
        config.sim().moiKgM2(),
        config.sim().startingPositionSi(),
        config.errors().size(),
        SafeMode.isActive(),
        Rootstock.VERSION,
        WPILibVersion.Version,
        kUnknown);
  }

  /**
   * The snapshot of an open-loop mechanism.
   *
   * @param config the config
   * @return the snapshot
   */
  public static MechanismConfigSnapshot of(SimpleConfig config) {
    MechanismUnits units = config.units();
    MotorSpec leader = config.motors().leader();

    return new MechanismConfigSnapshot(
        config.name(),
        config.kind().name(),
        leader.vendor(),
        "NONE (open loop)",
        "DEFAULTED",
        leader.outputMode().name(),
        leader.foc(),
        leader.deviceId(),
        leader.canBus(),
        config.motors().count() - 1,
        config.reduction().rotorPerOutput(),
        config.reduction().describe(),
        units.userPerOutputRotation(),
        units.siPerOutputRotation(),
        units.unitLabel(),
        units.siLabel(),
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        "NONE",
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        kNotApplicable,
        false,
        kNotApplicable,
        kNotApplicable,
        config.current().statorAmps(),
        config.current().supplyAmps(),
        "none",
        "none",
        signalsFor(MechanismKind.SIMPLE),
        config.sim().moiKgM2(),
        config.sim().startingPositionSi(),
        config.errors().size(),
        SafeMode.isActive(),
        Rootstock.VERSION,
        WPILibVersion.Version,
        kUnknown);
  }

  // -------------------------------------------------------------------------------------------
  // Rendering
  // -------------------------------------------------------------------------------------------

  /**
   * The log key this snapshot belongs under.
   *
   * @return for example {@code /Rootstock/Config/Elevator}
   */
  public String logKey() {
    return kLogRoot + name;
  }

  /**
   * A copy that names the vendor library version, once a backend that can report one has attached.
   *
   * @param version the vendor library version string
   * @return a new snapshot; this one is unchanged
   */
  public MechanismConfigSnapshot withVendorLibVersion(String version) {
    return new MechanismConfigSnapshot(
        name, kind, backend, controlLocation, controlLocationSource, outputMode, focEnabled,
        leaderCanId, canBus, followerCount, reductionRotorPerOutput, reductionDerivation,
        userPerOutputRotation, siPerOutputRotation, unitLabel, siLabel, softMin, softMax,
        kP, kI, kD, kS, kV, kA, kG, gravityMode, horizontalReference, gravityArmPositionOffsetRot,
        maxVelocity, maxAcceleration, jerk, useExpo, expoKvVoltsPerRps, expoKaVoltsPerRps2,
        statorAmps, supplyAmps, feedbackKind, homingKind, subscribedSignals, simMassOrMoi,
        simStartPosition, configErrorCount, safeMode, rootstockVersion, wpilibVersion, version);
  }

  /**
   * A copy that names which device signals were actually subscribed, once the backend has decided.
   *
   * @param signals a comma-separated list, for example {@code "position, velocity, statorCurrent"}
   * @return a new snapshot; this one is unchanged
   */
  public MechanismConfigSnapshot withSubscribedSignals(String signals) {
    return new MechanismConfigSnapshot(
        name, kind, backend, controlLocation, controlLocationSource, outputMode, focEnabled,
        leaderCanId, canBus, followerCount, reductionRotorPerOutput, reductionDerivation,
        userPerOutputRotation, siPerOutputRotation, unitLabel, siLabel, softMin, softMax,
        kP, kI, kD, kS, kV, kA, kG, gravityMode, horizontalReference, gravityArmPositionOffsetRot,
        maxVelocity, maxAcceleration, jerk, useExpo, expoKvVoltsPerRps, expoKaVoltsPerRps2,
        statorAmps, supplyAmps, feedbackKind, homingKind, signals, simMassOrMoi,
        simStartPosition, configErrorCount, safeMode, rootstockVersion, wpilibVersion,
        vendorLibVersion);
  }

  /**
   * Every field as a key/value pair, in declaration order, for a telemetry layer that publishes
   * field by field.
   *
   * <p>Values are {@link Double}, {@link Integer}, {@link Boolean} or {@link String}, matching the
   * record component's type, so a publisher can dispatch on the value's class without reflection
   * over records.
   *
   * @return an ordered map of field name to value
   */
  public Map<String, Object> fields() {
    Map<String, Object> out = new LinkedHashMap<>(64);
    out.put("name", name);
    out.put("kind", kind);
    out.put("backend", backend);
    out.put("controlLocation", controlLocation);
    out.put("controlLocationSource", controlLocationSource);
    out.put("outputMode", outputMode);
    out.put("focEnabled", focEnabled);
    out.put("leaderCanId", leaderCanId);
    out.put("canBus", canBus);
    out.put("followerCount", followerCount);
    out.put("reductionRotorPerOutput", reductionRotorPerOutput);
    out.put("reductionDerivation", reductionDerivation);
    out.put("userPerOutputRotation", userPerOutputRotation);
    out.put("siPerOutputRotation", siPerOutputRotation);
    out.put("unitLabel", unitLabel);
    out.put("siLabel", siLabel);
    out.put("softMin", softMin);
    out.put("softMax", softMax);
    out.put("kP", kP);
    out.put("kI", kI);
    out.put("kD", kD);
    out.put("kS", kS);
    out.put("kV", kV);
    out.put("kA", kA);
    out.put("kG", kG);
    out.put("gravityMode", gravityMode);
    out.put("horizontalReference", horizontalReference);
    out.put("gravityArmPositionOffsetRot", gravityArmPositionOffsetRot);
    out.put("maxVelocity", maxVelocity);
    out.put("maxAcceleration", maxAcceleration);
    out.put("jerk", jerk);
    out.put("useExpo", useExpo);
    out.put("expoKvVoltsPerRps", expoKvVoltsPerRps);
    out.put("expoKaVoltsPerRps2", expoKaVoltsPerRps2);
    out.put("statorAmps", statorAmps);
    out.put("supplyAmps", supplyAmps);
    out.put("feedbackKind", feedbackKind);
    out.put("homingKind", homingKind);
    out.put("subscribedSignals", subscribedSignals);
    out.put("simMassOrMoi", simMassOrMoi);
    out.put("simStartPosition", simStartPosition);
    out.put("configErrorCount", configErrorCount);
    out.put("safeMode", safeMode);
    out.put("rootstockVersion", rootstockVersion);
    out.put("wpilibVersion", wpilibVersion);
    out.put("vendorLibVersion", vendorLibVersion);
    return out;
  }

  /**
   * The whole snapshot as one string per field, for a log topic that carries a string array.
   *
   * @return {@code "kP = 80.0000"} and so on, in declaration order
   */
  public List<String> toStrings() {
    List<String> out = new ArrayList<>(48);
    for (Map.Entry<String, Object> entry : fields().entrySet()) {
      Object value = entry.getValue();
      String rendered =
          value instanceof Double d
              ? String.format(Locale.ROOT, "%.6f", d)
              : String.valueOf(value);
      out.add(entry.getKey() + " = " + rendered);
    }
    return List.copyOf(out);
  }

  /**
   * What was actually applied to the device, rendered in the vendor's own units.
   *
   * <p>This is the other half of the debuggability promise. {@link #toStrings()} says what the team
   * wrote; this says what the motor controller was told, after every conversion the library
   * performs — because the single most expensive kind of bug in this space is a conversion that
   * happened twice or not at all, and the only way to see it is to print both ends.
   *
   * @return a multi-line block, newline-terminated
   */
  public String vendorConfigDump() {
    String nl = System.lineSeparator();
    StringBuilder sb = new StringBuilder(768);
    sb.append("--- ").append(name).append(": what the device was actually told ---").append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  SensorToMechanismRatio   %.6f  (rotor rotations per output rotation)",
                reductionRotorPerOutput))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Slot0.kP                 %.6f V/rot   (= %.4f V/%s x %.6f %s/rot)",
                kP * siPerOutputRotation,
                kP,
                siLabel,
                siPerOutputRotation,
                siLabel))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Slot0.kD                 %.6f        (= %.4f x %.6f)",
                kD * siPerOutputRotation,
                kD,
                siPerOutputRotation))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Slot0.kV                 %.6f V/rps  (= %.4f x %.6f)",
                expoKvVoltsPerRps,
                kV,
                siPerOutputRotation))
        .append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Slot0.kA                 %.6f V/rps2 (= %.4f x %.6f)",
                expoKaVoltsPerRps2,
                kA,
                siPerOutputRotation))
        .append(nl);
    sb.append(String.format(Locale.ROOT, "  Slot0.kS                 %.6f V", kS)).append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Slot0.kG                 %.6f V  (GravityType %s)",
                kG,
                gravityMode))
        .append(nl);
    if ("COSINE".equals(gravityMode)) {
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  GravityArmPositionOffset %.6f rot  (negated from horizontalAt %.3f %s; the "
                      + "device silently clamps this to +/-0.25)",
                  gravityArmPositionOffsetRot,
                  horizontalReference,
                  unitLabel))
          .append(nl);
    }
    if (Double.isFinite(softMin) && Double.isFinite(softMax) && userPerOutputRotation != 0.0) {
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  SoftLimit reverse/fwd    %.4f / %.4f output rot  (from %.4f / %.4f %s)",
                  softMin / userPerOutputRotation,
                  softMax / userPerOutputRotation,
                  softMin,
                  softMax,
                  unitLabel))
          .append(nl);
    }
    if (Double.isFinite(maxVelocity) && userPerOutputRotation != 0.0) {
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  MotionMagicCruiseVel     %.4f rps    (from %.4f %s/s)",
                  maxVelocity / userPerOutputRotation,
                  maxVelocity,
                  unitLabel))
          .append(nl);
      sb.append(
              String.format(
                  Locale.ROOT,
                  "  MotionMagicAcceleration  %.4f rps2   (from %.4f %s/s2)",
                  maxAcceleration / userPerOutputRotation,
                  maxAcceleration,
                  unitLabel))
          .append(nl);
    }
    sb.append(
            String.format(
                Locale.ROOT,
                "  CurrentLimits            stator %.1f A, supply %.1f A",
                statorAmps,
                supplyAmps))
        .append(nl);
    sb.append("  Signals subscribed       ").append(subscribedSignals).append(nl);
    sb.append(
            String.format(
                Locale.ROOT,
                "  Versions                 Rootstock %s, WPILib %s, vendor %s",
                rootstockVersion,
                wpilibVersion,
                vendorLibVersion))
        .append(nl);
    return sb.toString();
  }

  @Override
  public String toString() {
    return "MechanismConfigSnapshot[" + name + " " + kind + ", " + configErrorCount + " error(s)]";
  }

  /**
   * The signals a mechanism of this kind subscribes to by default.
   *
   * <p>Recorded in the log so that a {@code NaN} in {@code MotorInputs} is self-explaining: a
   * velocity mechanism deliberately does not read position, and a reader six weeks later should
   * not have to know that.
   *
   * @param kind the mechanism kind
   * @return a comma-separated list
   */
  private static String signalsFor(MechanismKind kind) {
    return switch (kind) {
      case POSITION ->
          "position, velocity, appliedVolts, statorCurrent, supplyCurrent, temperature";
      case VELOCITY ->
          "velocity, appliedVolts, statorCurrent, supplyCurrent, temperature "
              + "(position NOT read — MotorInputs.positionRot is NaN by design)";
      case SIMPLE ->
          "appliedVolts, statorCurrent, supplyCurrent, temperature "
              + "(position and velocity NOT read)";
    };
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}

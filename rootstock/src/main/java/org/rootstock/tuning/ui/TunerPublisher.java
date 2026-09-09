package org.rootstock.tuning.ui;

import edu.wpi.first.networktables.BooleanEntry;
import edu.wpi.first.networktables.BooleanPublisher;
import edu.wpi.first.networktables.DoubleEntry;
import edu.wpi.first.networktables.DoublePublisher;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringArrayPublisher;
import edu.wpi.first.networktables.StringEntry;
import edu.wpi.first.networktables.StringPublisher;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.Rootstock;
import org.rootstock.tuning.TunableGains;
import org.rootstock.tuning.TuningRegistry;
import org.rootstock.tuning.persist.TunedValueStore;

/**
 * Every NetworkTables topic the tuning UI reads, published as plain NT4.
 *
 * <p><b>Plain NT4 and nothing else.</b> No structs, no protobuf, no logging-framework types on the
 * wire. That is what lets Elastic bind a Text Display to {@code /RootstockTuner/state} with no setup
 * and lets AdvantageScope graph {@code /RootstockTuner/plot/volts} with no setup, and it is what makes
 * the failure mode survivable: if the shipped layout is missing, a student can drag four widgets onto
 * a tab and be running in ninety seconds.
 *
 * <p><b>Nothing editable lives here.</b> Editable values live at {@code /Tuning/<Mechanism>/<key>} and
 * only there. This root carries wizard state, narration, plots, diagnostics, exports and the
 * per-mechanism metadata blob — the metadata deliberately <i>outside</i> {@code /Tuning} so it never
 * clutters the tuning tab a student is actually editing.
 *
 * <p>Publishers are created on first use and cached, so a topic that is never published costs
 * nothing, and a topic published every loop costs one cached handle.
 */
public final class TunerPublisher {

  /** The root every topic in this class hangs off. Never {@code /Tuning}. */
  public static final String kRoot = "/RootstockTuner";

  /** Where the per-mechanism metadata blob lives — outside {@code /Tuning} on purpose. */
  public static final String kMechanismsRoot = kRoot + "/Mechanisms";

  /** How many narration lines the rolling log keeps. */
  public static final int kLogLines = 40;

  private static final Map<String, DoublePublisher> s_doubles = new LinkedHashMap<>();
  private static final Map<String, StringPublisher> s_strings = new LinkedHashMap<>();
  private static final Map<String, BooleanPublisher> s_booleans = new LinkedHashMap<>();
  private static final Map<String, StringArrayPublisher> s_stringArrays = new LinkedHashMap<>();
  private static final Map<String, BooleanEntry> s_commands = new LinkedHashMap<>();
  private static final Map<String, StringEntry> s_stringCommands = new LinkedHashMap<>();
  private static final Map<String, DoubleEntry> s_numberCommands = new LinkedHashMap<>();
  private static final Map<String, String> s_lastMeta = new LinkedHashMap<>();
  private static final Deque<String> s_log = new ArrayDeque<>();

  private static NetworkTableInstance s_instance = NetworkTableInstance.getDefault();
  private static boolean s_installed;

  private TunerPublisher() {}

  /**
   * Publish the static topics and register the per-mechanism metadata republish with the tuning
   * slice.
   *
   * <p>Idempotent. Call it once, after the mechanisms are registered — normally from the robot
   * constructor, immediately after {@code RootstockRegistry.addAll(...)}.
   */
  public static void install() {
    if (s_installed) {
      return;
    }
    s_installed = true;
    string("/version")
        .set(
            "Rootstock "
                + Rootstock.VERSION
                + " / WPILib "
                + edu.wpi.first.wpilibj.util.WPILibVersion.Version);
    publishMechanismList();
    TuningRegistry.setMetadataPublisher(TunerPublisher::republishMetadata);
  }

  /**
   * Point the publisher at a specific NetworkTables instance.
   *
   * <p>For tests, which run against a private instance. Must be called before {@link #install()}.
   *
   * @param instance the instance to publish on
   */
  public static void setNetworkTableInstance(NetworkTableInstance instance) {
    if (instance != null) {
      s_instance = instance;
    }
  }

  // ---- wizard state and narration -----------------------------------------------------------

  /**
   * Publish the list of registered mechanism names.
   *
   * <p>The dashboard's ComboBox binds to this, so adding a mechanism adds a menu entry with no
   * layout edit.
   */
  public static void publishMechanismList() {
    List<TuningTarget> targets = TuningRegistry.targets();
    String[] names = new String[targets.size()];
    for (int i = 0; i < names.length; i++) {
      names[i] = targets.get(i).tuningName();
    }
    stringArray("/mechanisms").set(names);
  }

  /**
   * The currently selected mechanism.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   */
  public static void setSelected(String mechanism) {
    string("/selected").set(mechanism == null ? "" : mechanism);
  }

  /**
   * The wizard's state machine, as a word a student can read.
   *
   * @param state one of {@code IDLE}, {@code READY}, {@code PREFLIGHT}, {@code ARMED},
   *     {@code RUNNING}, {@code REVIEW}, {@code ABORTED}, {@code DONE}, {@code DISABLED_REPLAY}
   */
  public static void setState(String state) {
    string("/state").set(state == null ? "IDLE" : state);
  }

  /**
   * Mirror of the enable trigger, so the student can see that the robot agrees with their thumb.
   *
   * @param held whether the enable trigger is held
   */
  public static void setEnableHeld(boolean held) {
    bool("/enableHeld").set(held);
  }

  /**
   * The current step's position, title and narration.
   *
   * @param index the 1-based step number
   * @param count how many steps this recipe has
   * @param title e.g. {@code "Step 3 of 11 - Find kS"}
   * @param explanation the lesson: what this step teaches
   * @param willDo what the robot is about to do, in plain language
   * @param watchFor what the student should watch for while it happens
   * @param progress 0..1
   */
  public static void setStep(
      int index,
      int count,
      String title,
      String explanation,
      String willDo,
      String watchFor,
      double progress) {
    number("/step/index").set(index);
    number("/step/count").set(count);
    string("/step/title").set(nullToBlank(title));
    string("/step/explanation").set(nullToBlank(explanation));
    string("/step/willDo").set(nullToBlank(willDo));
    string("/step/watchFor").set(nullToBlank(watchFor));
    number("/step/progress").set(progress);
  }

  /**
   * Append one line of narration to the rolling log.
   *
   * <p>Rolling rather than unbounded because this is a dashboard widget, not a log file: the last
   * {@value #kLogLines} lines are what fits on a pit screen, and the log file already has the rest.
   *
   * @param line the line to add
   */
  public static void narrate(String line) {
    if (line == null || line.isBlank()) {
      return;
    }
    s_log.addLast(line);
    while (s_log.size() > kLogLines) {
      s_log.removeFirst();
    }
    stringArray("/log").set(s_log.toArray(new String[0]));
  }

  /**
   * The teaching/express mode the wizard is running in.
   *
   * @param mode {@code "TEACHING"} or {@code "EXPRESS"}
   */
  public static void setMode(String mode) {
    string("/mode").set(nullToBlank(mode));
  }

  /**
   * The verbatim acknowledgement text for a shared tuning controller, blank when the wizard has its
   * own port.
   *
   * @param reason the reason the team gave, verbatim
   */
  public static void setSharedControllerAck(String reason) {
    string("/sharedControllerAck").set(nullToBlank(reason));
  }

  /**
   * The verbatim acknowledgement text for running a gravity probe in coast, blank when idle mode is
   * BRAKE.
   *
   * @param reason the reason the team gave, verbatim
   */
  public static void setCoastRiskAck(String reason) {
    string("/coastRiskAck").set(nullToBlank(reason));
  }

  // ---- formative assessment -------------------------------------------------------------------

  /**
   * Publish a prediction question and its three plain-language options.
   *
   * @param question the question
   * @param options exactly three outcomes, in the order the ComboBox will show them
   */
  public static void setPrediction(String question, String[] options) {
    string("/predict/question").set(nullToBlank(question));
    stringArray("/predict/options").set(options == null ? new String[0] : options);
  }

  /**
   * Publish the score and the branched feedback for the last prediction.
   *
   * @param score e.g. {@code "Predictions: 7 of 9"}
   * @param feedback the coach text, populated after the step runs
   */
  public static void setPredictionResult(String score, String feedback) {
    string("/predict/score").set(nullToBlank(score));
    string("/predict/feedback").set(nullToBlank(feedback));
  }

  /**
   * The student's answer index, written by the dashboard ComboBox or by the D-pad.
   *
   * @return 0, 1 or 2; -1 when nothing has been answered
   */
  public static int predictionAnswer() {
    DoubleEntry entry =
        s_numberCommands.computeIfAbsent(
            "/predict/answer", s -> s_instance.getDoubleTopic(kRoot + s).getEntry(-1.0));
    return (int) Math.round(entry.get(-1.0));
  }

  // ---- plots ------------------------------------------------------------------------------------

  /**
   * Publish one sample of the two stacked plots.
   *
   * <p>Feedforward and feedback volts are published <b>separately</b> on purpose, and it is one of
   * the highest-value teaching artefacts in the design: a student who can see that the feedforward
   * line carries 95% of the voltage while the feedback line wobbles around zero has understood
   * feedforward-before-feedback in a way no paragraph achieves. {@link Double#NaN} means "not
   * observable" and graphs as a gap rather than as a lie.
   *
   * @param setpoint the profile setpoint, SI
   * @param measurement the measured position or velocity, SI
   * @param goal the final goal, SI — a flat line
   * @param volts total commanded volts
   * @param ffVolts the feedforward contribution, or NaN
   * @param fbVolts the feedback contribution, or NaN
   * @param velocity measured velocity, SI
   * @param amps stator current, or NaN
   */
  public static void plot(
      double setpoint,
      double measurement,
      double goal,
      double volts,
      double ffVolts,
      double fbVolts,
      double velocity,
      double amps) {
    number("/plot/setpoint").set(setpoint);
    number("/plot/measurement").set(measurement);
    number("/plot/goal").set(goal);
    number("/plot/error").set(setpoint - measurement);
    number("/plot/volts").set(volts);
    number("/plot/ffVolts").set(ffVolts);
    number("/plot/fbVolts").set(fbVolts);
    number("/plot/velocity").set(velocity);
    number("/plot/amps").set(amps);
  }

  // ---- results, diagnostics, safety ---------------------------------------------------------

  /**
   * Publish the outcome of one step.
   *
   * @param gain which gain was fitted, e.g. {@code "kS"}
   * @param value the suggested value
   * @param previous what it was before
   * @param headline e.g. {@code "kS = 0.220 V"}
   * @param quality e.g. {@code "Fit R2 0.981, RMSE 0.094 V - good"}
   * @param verdict the plain-language diagnosis
   * @param recommendation the plain-language action
   * @param warnings anything the student should know
   */
  public static void setResult(
      String gain,
      double value,
      double previous,
      String headline,
      String quality,
      String verdict,
      String recommendation,
      String[] warnings) {
    string("/result/gain").set(nullToBlank(gain));
    number("/result/value").set(value);
    number("/result/previous").set(previous);
    string("/result/headline").set(nullToBlank(headline));
    string("/result/quality").set(nullToBlank(quality));
    string("/result/verdict").set(nullToBlank(verdict));
    string("/result/recommendation").set(nullToBlank(recommendation));
    stringArray("/result/warnings").set(warnings == null ? new String[0] : warnings);
  }

  /**
   * Publish the measured step-response diagnostics.
   *
   * @param riseTimeSec rise time, seconds
   * @param overshootPct overshoot, percent
   * @param settleTimeSec settling time, seconds
   * @param steadyStateErrorSi residual error, SI
   * @param residualVolts residual voltage of the fit
   * @param dampingRatio the measured damping ratio
   * @param naturalFrequencyHz the measured natural frequency
   * @param oscillationHz the observed oscillation frequency, or NaN
   * @param classification the {@code ResponseClass} name
   * @param saturated whether the command saturated the battery
   */
  public static void setDiagnostics(
      double riseTimeSec,
      double overshootPct,
      double settleTimeSec,
      double steadyStateErrorSi,
      double residualVolts,
      double dampingRatio,
      double naturalFrequencyHz,
      double oscillationHz,
      String classification,
      boolean saturated) {
    number("/diagnostics/riseTimeSec").set(riseTimeSec);
    number("/diagnostics/overshootPct").set(overshootPct);
    number("/diagnostics/settleTimeSec").set(settleTimeSec);
    number("/diagnostics/steadyStateErrorSi").set(steadyStateErrorSi);
    number("/diagnostics/residualVolts").set(residualVolts);
    number("/diagnostics/dampingRatio").set(dampingRatio);
    number("/diagnostics/naturalFrequencyHz").set(naturalFrequencyHz);
    number("/diagnostics/oscillationHz").set(oscillationHz);
    string("/diagnostics/classification").set(nullToBlank(classification));
    bool("/diagnostics/saturated").set(saturated);
  }

  /**
   * Publish the safety state.
   *
   * <p>{@code message} carries the student-facing sentence for an abort <b>and</b> the precondition
   * message when an arm was refused, because "why will it not start" and "why did it stop" are the
   * same question asked at two different moments.
   *
   * @param tripped whether the supervisor has aborted
   * @param reason the {@code AbortReason} name
   * @param message the student-facing sentence
   * @param envelopeJson a JSON dump of the active safety envelope
   */
  public static void setSafety(boolean tripped, String reason, String message, String envelopeJson) {
    bool("/safety/tripped").set(tripped);
    string("/safety/reason").set(nullToBlank(reason));
    string("/safety/message").set(nullToBlank(message));
    string("/safety/envelope").set(nullToBlank(envelopeJson));
  }

  /**
   * Publish the system-identification fit summary and the plain WPILog path.
   *
   * @param r2 the coefficient of determination
   * @param rmseVolts the root-mean-square residual, volts
   * @param samples how many samples the fit consumed
   * @param logPath the path of the SysId-loadable WPILog that was written
   */
  public static void setSysId(double r2, double rmseVolts, int samples, String logPath) {
    number("/sysid/r2").set(r2);
    number("/sysid/rmseVolts").set(rmseVolts);
    number("/sysid/samples").set(samples);
    string("/lastSysIdLog").set(nullToBlank(logPath));
  }

  // ---- exports and commands -------------------------------------------------------------------

  /**
   * Publish the three export artefacts so a student with only a Driver Station can copy them.
   *
   * @param java the paste-ready Java block
   * @param json the committable JSON baseline
   * @param report the markdown session report
   */
  public static void setExports(String java, String json, String report) {
    string("/export/java").set(nullToBlank(java));
    string("/export/json").set(nullToBlank(json));
    string("/export/report").set(nullToBlank(report));
  }

  /**
   * Read and clear a momentary command written by the dashboard.
   *
   * <p>Consumed and reset in the same loop, so a button press is one event rather than a state a
   * student has to remember to turn off. The names are the ones the layout binds:
   * {@code start, accept, retry, back, skip, abort, save, saveAndExport, preflight}.
   *
   * @param name the command name, without a leading slash
   * @return true exactly once per press
   */
  public static boolean consumeCommand(String name) {
    BooleanEntry entry =
        s_commands.computeIfAbsent(
            name, n -> s_instance.getBooleanTopic(kRoot + "/cmd/" + n).getEntry(false));
    if (!entry.get(false)) {
      return false;
    }
    entry.set(false);
    return true;
  }

  /**
   * Read and clear the mode-change command.
   *
   * @return the requested mode, or empty when none is pending
   */
  public static Optional<String> consumeModeRequest() {
    StringEntry entry =
        s_stringCommands.computeIfAbsent(
            "setMode", n -> s_instance.getStringTopic(kRoot + "/cmd/" + n).getEntry(""));
    String value = entry.get("");
    if (value == null || value.isBlank()) {
      return Optional.empty();
    }
    entry.set("");
    return Optional.of(value.trim().toUpperCase(Locale.ROOT));
  }

  // ---- metadata ---------------------------------------------------------------------------------

  /**
   * Republish every registered mechanism's metadata blob, skipping any that has not changed.
   *
   * <p>Called from the {@code "Tuning"} slice, which is the rate-gated lane. Skipping unchanged blobs
   * is what keeps a stopped robot's NT traffic at zero: the metadata is a boot-time fact plus a
   * provenance record, and neither changes at 50 Hz.
   */
  public static void republishMetadata() {
    for (TuningTarget target : TuningRegistry.targets()) {
      String name = target.tuningName();
      String json = metaJson(name);
      if (json.equals(s_lastMeta.get(name))) {
        continue;
      }
      s_lastMeta.put(name, json);
      string(kMechanismsRoot.substring(kRoot.length()) + "/" + name + "/meta").set(json);
    }
  }

  /**
   * The metadata blob for one mechanism, as a JSON string.
   *
   * <p>Everything a UI needs to render a gain that it cannot work out from the number alone: the
   * unit, the slider range, the step, and — the load-bearing field — which of the four load tiers or
   * four wizard steps produced it. That last one is what makes "where did this number come from?"
   * answerable from a dashboard alone.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @return the JSON, or {@code "{}"} when nothing by that name is registered
   */
  public static String metaJson(String mechanism) {
    Optional<TuningTarget> found = TuningRegistry.target(mechanism);
    Optional<TunableGains> gains = TuningRegistry.gainsFor(mechanism);
    if (found.isEmpty() || gains.isEmpty()) {
      return "{}";
    }
    TuningTarget target = found.get();
    TunedValueStore.Resolved resolved =
        TunedValueStore.resolve(mechanism, TuningRegistry.configHash(target), target.gains());

    StringBuilder sb = new StringBuilder(768);
    sb.append('{');
    field(sb, "name", mechanism).append(',');
    field(sb, "archetype", target.archetype().name()).append(',');
    field(sb, "siDomain", target.siDomain().name()).append(',');
    field(sb, "controlLocation", target.controlLocation().name()).append(',');
    field(sb, "gravityMode", target.gravityMode().name()).append(',');
    field(sb, "configHash", TuningRegistry.configHash(target)).append(',');

    sb.append("\"travelLimits\":{")
        .append("\"min\":")
        .append(json(target.travelLimits().min()))
        .append(",\"max\":")
        .append(json(target.travelLimits().max()))
        .append(",\"softMargin\":")
        .append(json(target.travelLimits().softMargin()))
        .append("},");

    sb.append("\"gains\":{");
    GainId[] ids = GainId.values();
    for (int i = 0; i < ids.length; i++) {
      GainId id = ids[i];
      double max = sliderMax(id, target);
      if (i > 0) {
        sb.append(',');
      }
      sb.append('"').append(id.key()).append("\":{");
      field(sb, "unit", id.unitFor(target.siDomain())).append(',');
      sb.append("\"value\":").append(json(gains.get().get().get(id))).append(',');
      sb.append("\"min\":0.0,\"max\":").append(json(max)).append(',');
      sb.append("\"step\":").append(json(max / 1000.0)).append(',');
      field(sb, "source", resolved.sourceOf(id).name());
      sb.append('}');
    }
    sb.append("},");

    field(sb, "conversion", gains.get().describeConversion()).append(',');
    field(sb, "libraryVersion", Rootstock.VERSION).append(',');
    field(sb, "wpilibVersion", edu.wpi.first.wpilibj.util.WPILibVersion.Version);
    sb.append('}');
    return sb.toString();
  }

  /** Clears every cached publisher, so one test cannot see another test's topics. */
  public static void resetForTest() {
    s_doubles.clear();
    s_strings.clear();
    s_booleans.clear();
    s_stringArrays.clear();
    s_commands.clear();
    s_stringCommands.clear();
    s_numberCommands.clear();
    s_lastMeta.clear();
    s_log.clear();
    s_installed = false;
    s_instance = NetworkTableInstance.getDefault();
  }

  // ---- internals ---------------------------------------------------------------------------

  /**
   * A plausible upper bound for a gain's slider, derived from the plant prior where physics gives us
   * one and from experience where it does not.
   *
   * <p>The feedforward terms have real priors — the motor curve says what kV, kA and kG ought to be —
   * so their sliders end at six times the prior, which is the same band the fit sanity-check uses. The
   * feedback terms have no prior, so their bounds are generous defaults; a slider bound is a display
   * hint and never clamps a number a human deliberately typed.
   */
  private static double sliderMax(GainId id, TuningTarget target) {
    double prior;
    switch (id) {
      case KV:
        prior = target.plantPrior().kVprior();
        break;
      case KA:
        prior = target.plantPrior().kAprior();
        break;
      case KG:
        prior = target.plantPrior().gravityVoltsPrior();
        break;
      case KP:
        return 400.0;
      case KD:
        return 40.0;
      case KI:
        return 10.0;
      case KS:
      default:
        return 3.0;
    }
    if (!Double.isFinite(prior) || prior <= 0) {
      return 12.0;
    }
    return 6.0 * prior;
  }

  private static StringBuilder field(StringBuilder sb, String key, String value) {
    return sb.append('"').append(key).append("\":\"").append(escape(value)).append('"');
  }

  private static String json(double value) {
    // JSON has no NaN or Infinity. Rendering them as null keeps the blob parseable, and an untuned
    // gain shows as an empty field rather than as a plausible-looking zero.
    return Double.isFinite(value) ? String.format(Locale.ROOT, "%.6g", value) : "null";
  }

  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder(value.length() + 8);
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        sb.append('\\').append(c);
      } else if (c == '\n') {
        sb.append("\\n");
      } else if (c == '\r') {
        sb.append("\\r");
      } else if (c == '\t') {
        sb.append("\\t");
      } else if (c < 0x20) {
        sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }

  private static String nullToBlank(String value) {
    return value == null ? "" : value;
  }

  private static DoublePublisher number(String suffix) {
    return s_doubles.computeIfAbsent(suffix, s -> s_instance.getDoubleTopic(kRoot + s).publish());
  }

  private static StringPublisher string(String suffix) {
    return s_strings.computeIfAbsent(suffix, s -> s_instance.getStringTopic(kRoot + s).publish());
  }

  private static BooleanPublisher bool(String suffix) {
    return s_booleans.computeIfAbsent(suffix, s -> s_instance.getBooleanTopic(kRoot + s).publish());
  }

  private static StringArrayPublisher stringArray(String suffix) {
    return s_stringArrays.computeIfAbsent(
        suffix, s -> s_instance.getStringArrayTopic(kRoot + s).publish());
  }
}

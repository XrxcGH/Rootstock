package org.rootstock.tuning.ui;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.alert.Alerts;
import org.rootstock.core.alert.MatchImpact;
import org.rootstock.tuning.TunableDouble;
import org.rootstock.tuning.TuningRegistry;

/**
 * Writes the Elastic dashboard layout for the tuning tab, generated from the registry rather than
 * hand-authored.
 *
 * <p><b>Why it is generated.</b> Every gain widget binds to {@code /Tuning/<Mechanism>/<gain>}, and
 * the mechanism name is part of the path. A hand-dragged layout therefore goes stale the moment a
 * team adds a mechanism, and a stale layout is discovered on a Saturday morning. A team that adds a
 * mechanism regenerates instead of dragging widgets.
 *
 * <p><b>The seven gain widgets are exactly {@code GainId.values()}</b>, so a gain that exists in the
 * record but not on the dashboard is structurally impossible.
 *
 * <p><b>Why Elastic.</b> It ships with WPILib, it is already open on the driver-station laptop, its
 * editable widgets cover everything a tuner needs, and it survives 2027 — Shuffleboard and
 * SmartDashboard do not. The decisive argument is the failure mode: if this file is missing, a
 * student can drag four widgets onto a tab and be running in ninety seconds, because everything on
 * the wire is a plain NT4 double or string.
 *
 * <p>Elastic's layout JSON is not published as a formal public contract, so it could move within a
 * season. That is why the emitted file carries {@code "version": 1.0} and why regenerating is one
 * command: a schema change must degrade to "run it again", never to "the drivers have no dashboard".
 */
public final class ElasticLayoutGenerator {

  /** The file name the deploy directory serves for remote layout download. */
  public static final String kFileName = "elastic-tuning-layout.json";

  /** Elastic's grid unit, in pixels. Every position and size below is a multiple of it. */
  public static final double kGridSize = 128.0;

  /** The layout schema version stamped into the file. */
  public static final double kLayoutVersion = 1.0;

  /** The alert group a write failure is reported under. */
  public static final String kAlertGroup = "Tuning";

  private static final ObjectMapper kMapper = new ObjectMapper();

  private ElasticLayoutGenerator() {}

  /**
   * Write the layout for every registered mechanism to a file.
   *
   * <p>Never throws: a layout that cannot be written raises a {@code PIT_ONLY} warning, because a
   * robot that refuses to boot over a dashboard file is a worse outcome than a dashboard a student
   * has to lay out by hand.
   *
   * @param destination the file to write; parent directories are created
   * @return the path written, or empty on failure
   */
  public static Optional<Path> write(Path destination) {
    String json = toJson();
    try {
      Path parent = destination.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(destination, json, StandardCharsets.UTF_8);
      return Optional.of(destination);
    } catch (IOException | RuntimeException e) {
      Alerts.warning(
              kAlertGroup,
              "Could not write the Elastic tuning layout to "
                  + destination
                  + " ("
                  + e
                  + "). Nothing on the robot is broken — you will have to lay the tuning tab out by"
                  + " hand, which takes about ninety seconds because every topic is a plain NT4"
                  + " double or string. Fix: check the directory exists and is writable.",
              MatchImpact.PIT_ONLY)
          .set(true);
      return Optional.empty();
    }
  }

  /**
   * The layout as a JSON string.
   *
   * <p>Exposed separately from {@link #write(Path)} so a build task, a test, or a topic under
   * {@code /RootstockTuner/} can all use the same bytes.
   *
   * @return the layout JSON, pretty-printed
   */
  public static String toJson() {
    List<Object> tabs = new ArrayList<>();
    tabs.add(wizardTab());
    for (TuningTarget target : TuningRegistry.targets()) {
      tabs.add(mechanismTab(target));
    }

    Map<String, Object> root = new LinkedHashMap<>();
    root.put("version", kLayoutVersion);
    root.put("grid_size", (int) kGridSize);
    root.put("tabs", tabs);
    try {
      return kMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root) + System.lineSeparator();
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      // The object graph is built here out of maps, lists, strings and doubles, so this cannot
      // happen; returning a valid empty layout rather than throwing keeps the promise that nothing
      // in this package takes a robot down for a dashboard.
      return "{\"version\":1.0,\"grid_size\":128,\"tabs\":[]}";
    }
  }

  // ---- tabs ---------------------------------------------------------------------------------

  /**
   * The {@code RootstockTuner} tab: state, narration, the two stacked graphs, and the prediction
   * widgets.
   *
   * <p>The two graphs are placed adjacently and sized identically <b>on purpose</b>: they reproduce
   * the layout of WPILib's own browser tuning tutorials, setpoint-versus-measurement above and
   * voltage below. A student who learned the shape of a good response in the browser sees literally
   * the same shape on their elevator, and that continuity is worth more than any feature here.
   */
  private static Map<String, Object> wizardTab() {
    List<Object> widgets = new ArrayList<>();

    widgets.add(combo("Mechanism", "/RootstockTuner/mechanisms", 0, 0, 2, 1));
    widgets.add(text("State", "/RootstockTuner/state", 2, 0, 2, 1));
    widgets.add(text("Step", "/RootstockTuner/step/title", 4, 0, 4, 1));
    widgets.add(numberBar("Progress", "/RootstockTuner/step/progress", 8, 0, 2, 1, 0.0, 1.0));
    widgets.add(booleanBox("Enable held", "/RootstockTuner/enableHeld", 10, 0, 1, 1));

    widgets.add(text("What this step teaches", "/RootstockTuner/step/explanation", 0, 1, 4, 3));
    widgets.add(text("What the robot will do", "/RootstockTuner/step/willDo", 0, 4, 4, 2));
    widgets.add(text("Watch for", "/RootstockTuner/step/watchFor", 0, 6, 4, 2));

    widgets.add(graph("Setpoint vs measured", 4, 1, 5, 3,
        List.of("/RootstockTuner/plot/setpoint", "/RootstockTuner/plot/measurement")));
    widgets.add(graph("Commanded volts (FF vs FB)", 4, 4, 5, 3,
        List.of("/RootstockTuner/plot/volts", "/RootstockTuner/plot/ffVolts", "/RootstockTuner/plot/fbVolts")));

    widgets.add(booleanBox("Safety tripped", "/RootstockTuner/safety/tripped", 9, 1, 2, 1));
    widgets.add(text("Safety", "/RootstockTuner/safety/message", 9, 2, 2, 2));
    widgets.add(text("Result", "/RootstockTuner/result/headline", 9, 4, 2, 1));
    widgets.add(text("Diagnosis", "/RootstockTuner/result/verdict", 9, 5, 2, 2));
    widgets.add(text("Recommendation", "/RootstockTuner/result/recommendation", 9, 7, 2, 2));

    widgets.add(text("Predict: the question", "/RootstockTuner/predict/question", 0, 8, 4, 2));
    widgets.add(combo("Predict: your answer", "/RootstockTuner/predict/options", 4, 7, 2, 1));
    widgets.add(text("Predict: how you did", "/RootstockTuner/predict/feedback", 4, 8, 3, 2));
    widgets.add(text("Prediction score", "/RootstockTuner/predict/score", 7, 7, 2, 1));
    widgets.add(text("Mode", "/RootstockTuner/mode", 7, 8, 2, 1));
    widgets.add(alerts("Alerts", 9, 9, 2, 2));

    return tab("RootstockTuner", widgets);
  }

  /**
   * One tab per mechanism: the seven gains, then the ten {@code ControlConfig} values, then the two
   * LQR preference sliders.
   *
   * <p>Every editable widget on this tab binds to {@code /Tuning/<Mechanism>/…} — that is the only
   * editable path in the whole design. The LQR sliders are student <i>preferences</i>, inputs to a
   * solver that are never written to a motor controller, so they live under {@code /RootstockTuner/}
   * instead.
   */
  private static Map<String, Object> mechanismTab(TuningTarget target) {
    String name = target.tuningName();
    String prefix = TuningRegistry.kPrefix + name + "/";
    List<Object> widgets = new ArrayList<>();

    int column = 0;
    int row = 0;
    for (GainId id : GainId.values()) {
      widgets.add(
          text(
              id.key() + "  (" + id.unitFor(target.siDomain()) + ")",
              prefix + id.key(),
              column * 2,
              row,
              2,
              1));
      widgets.add(text(id.key() + " applied", prefix + id.key() + "/applied", column * 2, row + 1, 2, 1));
      column++;
      if (column == 4) {
        column = 0;
        row += 2;
      }
    }

    row = 4;
    column = 0;
    for (String control : TuningRegistry.kControlTunables) {
      widgets.add(text(control, prefix + control, column * 2, row, 2, 1));
      column++;
      if (column == 5) {
        column = 0;
        row += 1;
      }
    }

    row += 2;
    widgets.add(
        slider(
            "Max acceptable error",
            TunerPublisher.kRoot + "/" + name + "/lqr/maxError",
            0,
            row,
            3,
            1,
            0.0,
            Math.max(0.01, target.travelLimits().range() / 4.0)));
    widgets.add(
        slider(
            "Max control effort (V)",
            TunerPublisher.kRoot + "/" + name + "/lqr/maxVolts",
            3,
            row,
            3,
            1,
            0.0,
            12.0));

    return tab(name, widgets);
  }

  // ---- widget builders ------------------------------------------------------------------------

  private static Map<String, Object> tab(String name, List<Object> containers) {
    Map<String, Object> grid = new LinkedHashMap<>();
    grid.put("layouts", List.of());
    grid.put("containers", containers);
    Map<String, Object> tab = new LinkedHashMap<>();
    tab.put("name", name);
    tab.put("grid_layout", grid);
    return tab;
  }

  private static Map<String, Object> container(
      String title, String type, int x, int y, int w, int h, Map<String, Object> properties) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("title", title);
    out.put("x", x * kGridSize);
    out.put("y", y * kGridSize);
    out.put("width", w * kGridSize);
    out.put("height", h * kGridSize);
    out.put("type", type);
    out.put("properties", properties);
    return out;
  }

  private static Map<String, Object> props(String topic) {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("topic", topic);
    p.put("period", 0.033);
    return p;
  }

  private static Map<String, Object> text(String title, String topic, int x, int y, int w, int h) {
    Map<String, Object> p = props(topic);
    p.put("show_submit_button", true);
    return container(title, "Text Display", x, y, w, h, p);
  }

  private static Map<String, Object> booleanBox(
      String title, String topic, int x, int y, int w, int h) {
    Map<String, Object> p = props(topic);
    p.put("true_color", 4283215696L);
    p.put("false_color", 4294198070L);
    p.put("true_icon", "None");
    p.put("false_icon", "None");
    return container(title, "Boolean Box", x, y, w, h, p);
  }

  private static Map<String, Object> numberBar(
      String title, String topic, int x, int y, int w, int h, double min, double max) {
    Map<String, Object> p = props(topic);
    p.put("min_value", min);
    p.put("max_value", max);
    p.put("divisions", 5);
    p.put("inverted", false);
    p.put("orientation", "horizontal");
    return container(title, "Number Bar", x, y, w, h, p);
  }

  private static Map<String, Object> slider(
      String title, String topic, int x, int y, int w, int h, double min, double max) {
    Map<String, Object> p = props(topic);
    p.put("min_value", min);
    p.put("max_value", max);
    p.put("divisions", 100);
    p.put("update_continuously", false);
    return container(title, "Number Slider", x, y, w, h, p);
  }

  private static Map<String, Object> combo(String title, String topic, int x, int y, int w, int h) {
    Map<String, Object> p = props(topic);
    p.put("sort_options", false);
    return container(title, "ComboBox Chooser", x, y, w, h, p);
  }

  private static Map<String, Object> graph(
      String title, int x, int y, int w, int h, List<String> topics) {
    // Elastic binds a graph to one topic and shows additional series by path; the first topic is the
    // widget's own binding and the rest are listed so a student can see, in the file, exactly which
    // three lines the voltage plot is supposed to carry.
    Map<String, Object> p = props(topics.get(0));
    p.put("time_displayed", 10.0);
    p.put("series", topics);
    return container(title, "Graph", x, y, w, h, p);
  }

  private static Map<String, Object> alerts(String title, int x, int y, int w, int h) {
    return container(title, "Alerts", x, y, w, h, props("/SmartDashboard/Rootstock"));
  }

  /**
   * The full NT key of one standalone tunable, for a team hand-editing the generated file.
   *
   * <p>Exposed because the generated tab covers mechanisms; a {@code tunable("Vision", …)} lives in a
   * namespace that is not a mechanism and is therefore not on any generated tab, and a student adding
   * that widget by hand needs the exact path.
   *
   * @param tunable the tunable
   * @return its full NT key
   */
  public static String topicOf(TunableDouble tunable) {
    return tunable.fullKey();
  }
}

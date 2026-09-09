package org.rootstock.tuning.persist;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.rootstock.control.GainId;
import org.rootstock.control.Gains;
import org.rootstock.control.TuningTarget;
import org.rootstock.core.Rootstock;
import org.rootstock.core.compat.Platform;
import org.rootstock.core.config.PersistentStore;
import org.rootstock.tuning.TunableGains;
import org.rootstock.tuning.TuningRegistry;

/**
 * Gets a Saturday of tuning back into source, so it survives a redeploy and survives the student who
 * did it graduating.
 *
 * <p>The runtime file that {@link TunedValueStore} writes is the safety net. The <i>committed</i>
 * file is the goal, because it is the artifact that outlives a season. This class produces the three
 * write-back paths, each of them one action:
 *
 * <ol>
 *   <li>{@link #writeDeployBaseline()} — the JSON a mentor can review as a git diff;
 *   <li>{@link #toJava(String)} — a paste-ready block for the team's own config class;
 *   <li>{@link #markdownReport()} — the session record a student attaches to a pull request.
 * </ol>
 *
 * <p><b>We do not regenerate the team's source.</b> That is tempting and wrong: it puts a robot
 * program in the business of rewriting its own source, it fights the team's formatter, and it breaks
 * exactly the reviewability that makes a committed file valuable. We generate a block to paste and a
 * JSON file to commit, and a human decides.
 */
public final class ValueExporter {

  /** The generated-Java file name, written beside {@code gains.json} for a student to copy. */
  public static final String kJavaSuffix = "-gains.java.txt";

  /** The markdown session-report file name. */
  public static final String kReportFileName = "tuning-report.md";

  private ValueExporter() {}

  /**
   * Write the whole robot's tuned values as a committable baseline.
   *
   * <p><b>In simulation</b> this writes {@code src/main/deploy/rootstock/gains.json} directly, because
   * that path is inside the project and the result is a git diff a mentor can review. <b>On the
   * robot</b> it writes {@code Platform.persistentDir()/rootstock/gains-for-commit.json} instead, plus
   * a console line telling the student to copy it — because the deploy directory is the deploy task's
   * territory and writing there invites a silent revert on the next deploy.
   *
   * @return the path written, or empty when nothing could be written
   */
  public static Optional<Path> writeDeployBaseline() {
    Map<String, Object> file = new LinkedHashMap<>();
    Map<String, Object> mechanisms = new LinkedHashMap<>();
    for (TuningTarget target : TuningRegistry.targets()) {
      String name = target.tuningName();
      Optional<TunableGains> gains = TuningRegistry.gainsFor(name);
      if (gains.isEmpty()) {
        continue;
      }
      Map<String, Object> entry =
          TunedValueStore.mechanismBlock(
              TuningRegistry.configHash(target), gains.get().get(), Map.of(), Map.of());
      entry.put("archetype", target.archetype().name());
      entry.put("siDomain", target.siDomain().name());
      Map<String, Double> setpoints = TunedValueStore.setpoints(name);
      if (!setpoints.isEmpty()) {
        entry.put("setpoints", new LinkedHashMap<String, Object>(setpoints));
      }
      mechanisms.put(name, entry);
    }
    file.put("mechanisms", mechanisms);
    TunedValueStore.stamp(file);

    if (Platform.isSimulation()) {
      PersistentStore store = PersistentStore.deploy();
      if (!store.writeJson(TunedValueStore.kFileName, file)) {
        return Optional.empty();
      }
      Path path = store.resolve(TunedValueStore.kFileName);
      System.out.println(
          "[Rootstock] Wrote "
              + path
              + ". That file is inside your project — commit it, and every robot you deploy to gets"
              + " these gains.");
      return Optional.of(path);
    }

    PersistentStore store = PersistentStore.persistent();
    if (!store.writeJson(TunedValueStore.kCommitFileName, file)) {
      return Optional.empty();
    }
    Path path = store.resolve(TunedValueStore.kCommitFileName);
    System.out.println(
        "[Rootstock] Wrote "
            + path
            + " on the robot. Copy it to src/main/deploy/rootstock/gains.json in your project and"
            + " commit it, or these numbers live only on this roboRIO. On a Windows laptop:"
            + " scp lvuser@roboRIO-<team>-FRC.local:"
            + path
            + " src/main/deploy/rootstock/gains.json");
    return Optional.of(path);
  }

  /**
   * A paste-ready Java block for one mechanism.
   *
   * <p>Matches the conventions the template mandates — {@code UPPER_SNAKE_CASE} constants, the unit
   * in a trailing comment, four-space indent, no {@code m_} prefix on constants — so it drops
   * straight into a one-file config class.
   *
   * <p><b>It emits named-field construction, never a seven-double constructor.</b> A positional
   * seven-argument gain constructor is precisely the bug class this library exists to delete — two
   * arguments transposed compiles, deploys, and destroys a mechanism — and generated code does not
   * get an exemption from a rule that exists because humans get it wrong.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @return the block, or an explanatory comment when the mechanism is not registered
   */
  public static String toJava(String mechanism) {
    Optional<TunableGains> found = TuningRegistry.gainsFor(mechanism);
    if (found.isEmpty()) {
      return "// Rootstock ValueExporter: no mechanism named \""
          + mechanism
          + "\" is registered, so there is nothing to export. Registered mechanisms: "
          + TuningRegistry.targets().stream().map(TuningTarget::tuningName).toList();
    }
    TunableGains gains = found.get();
    Gains g = gains.get();
    TuningTarget target = gains.target();
    String constant = toConstantName(mechanism);

    StringBuilder sb = new StringBuilder(768);
    sb.append("// ---- ")
        .append(mechanism)
        .append(" ---- generated by Rootstock ValueExporter ")
        .append(Rootstock.VERSION)
        .append(System.lineSeparator());
    sb.append("// Plant: ").append(target.plantPrior().describe()).append(System.lineSeparator());
    sb.append("// Travel: ")
        .append(target.travelLimits().describe(unitLabel(target)))
        .append(System.lineSeparator());
    sb.append("// Conversion into the device: ")
        .append(gains.describeConversion())
        .append(System.lineSeparator());
    sb.append("public static final Gains ")
        .append(constant)
        .append("_GAINS =")
        .append(System.lineSeparator());
    sb.append(
            String.format(
                Locale.ROOT,
                "    Gains.pid(/* kP %s */ %s, /* kI %s */ %s, /* kD %s */ %s)",
                GainId.KP.unitFor(target.siDomain()),
                trim(g.kP()),
                GainId.KI.unitFor(target.siDomain()),
                trim(g.kI()),
                GainId.KD.unitFor(target.siDomain()),
                trim(g.kD())))
        .append(System.lineSeparator());
    sb.append(field("withKs", g.kS(), GainId.KS.unitFor(target.siDomain())));
    sb.append(field("withKv", g.kV(), GainId.KV.unitFor(target.siDomain())));
    sb.append(field("withKa", g.kA(), GainId.KA.unitFor(target.siDomain())));
    sb.append(fieldLast("withKg", g.kG(), GainId.KG.unitFor(target.siDomain())));
    return sb.toString();
  }

  /**
   * The paste-ready Java for every registered mechanism, one block after another.
   *
   * @return the concatenated blocks; a comment when nothing is registered
   */
  public static String toJava() {
    List<TuningTarget> targets = TuningRegistry.targets();
    if (targets.isEmpty()) {
      return "// Rootstock ValueExporter: no mechanism is registered with the tuning system yet."
          + " Fix: pass your mechanisms to RootstockRegistry.addAll(...) in your Robot constructor.";
    }
    StringBuilder sb = new StringBuilder(1024);
    for (TuningTarget target : targets) {
      sb.append(toJava(target.tuningName()))
          .append(System.lineSeparator())
          .append(System.lineSeparator());
    }
    return sb.toString();
  }

  /**
   * Write the paste-ready Java for one mechanism to a file the student can pull off the robot.
   *
   * <p>Also printed to the console, because a student with only a Driver Station open can select the
   * block out of the console and paste it — which is the whole point on a Saturday with no laptop
   * privileges.
   *
   * @param mechanism the mechanism's {@code tuningName()}
   * @return the path written, or empty on failure
   */
  public static Optional<Path> writeJava(String mechanism) {
    String block = toJava(mechanism);
    System.out.println(block);
    PersistentStore store = PersistentStore.persistent();
    String name = mechanism + kJavaSuffix;
    if (!store.writeText(name, block)) {
      return Optional.empty();
    }
    return Optional.of(store.resolve(name));
  }

  /**
   * A full session record: every mechanism, every gain, where each value came from, and the fit
   * quality that produced it.
   *
   * <p>This is the artifact a student attaches to a pull request, and it is the closest thing there
   * is to an answer for <i>"the person who understood this graduated"</i>.
   *
   * @return the report, in markdown
   */
  public static String markdownReport() {
    StringBuilder sb = new StringBuilder(2048);
    sb.append("# Rootstock tuning report").append(System.lineSeparator());
    sb.append(System.lineSeparator());
    sb.append("Rootstock ")
        .append(Rootstock.VERSION)
        .append(" | WPILib ")
        .append(edu.wpi.first.wpilibj.util.WPILibVersion.Version)
        .append(" | ")
        .append(Platform.isSimulation() ? "simulation" : "robot")
        .append(System.lineSeparator());
    sb.append(System.lineSeparator());

    List<TuningTarget> targets = TuningRegistry.targets();
    if (targets.isEmpty()) {
      sb.append("No mechanism is registered with the tuning system.").append(System.lineSeparator());
      return sb.toString();
    }

    for (TuningTarget target : targets) {
      String name = target.tuningName();
      Optional<TunableGains> found = TuningRegistry.gainsFor(name);
      if (found.isEmpty()) {
        continue;
      }
      TunableGains gains = found.get();
      TunedValueStore.Resolved resolved =
          TunedValueStore.resolve(name, TuningRegistry.configHash(target), target.gains());

      sb.append("## ").append(name).append(System.lineSeparator());
      sb.append(System.lineSeparator());
      sb.append("- Archetype: `").append(target.archetype().name()).append("`")
          .append(System.lineSeparator());
      sb.append("- SI domain: `").append(target.siDomain().name()).append("`")
          .append(System.lineSeparator());
      sb.append("- Control location: `").append(target.controlLocation().name()).append("`")
          .append(System.lineSeparator());
      sb.append("- Config hash: `").append(TuningRegistry.configHash(target)).append("`")
          .append(System.lineSeparator());
      sb.append("- Plant: ").append(target.plantPrior().describe()).append(System.lineSeparator());
      sb.append("- Conversion: ").append(gains.describeConversion())
          .append(System.lineSeparator());
      sb.append(System.lineSeparator());
      sb.append("| gain | value | unit | came from |").append(System.lineSeparator());
      sb.append("|---|---|---|---|").append(System.lineSeparator());
      for (GainId id : GainId.values()) {
        sb.append("| `")
            .append(id.key())
            .append("` | ")
            .append(trim(gains.get().get(id)))
            .append(" | ")
            .append(id.unitFor(target.siDomain()))
            .append(" | ")
            .append(resolved.sourceOf(id).explain())
            .append(" |")
            .append(System.lineSeparator());
      }
      sb.append(System.lineSeparator());
      if (gains.rejectionCount() > 0) {
        sb.append("**")
            .append(gains.rejectionCount())
            .append(" config write(s) were REJECTED by the device this session.** Compare each")
            .append(" slider against its `/applied` echo to find which value came back different.")
            .append(System.lineSeparator())
            .append(System.lineSeparator());
      }
      if (!resolved.warnings().isEmpty()) {
        sb.append("Warnings:").append(System.lineSeparator());
        for (String warning : resolved.warnings()) {
          sb.append("- ").append(warning).append(System.lineSeparator());
        }
        sb.append(System.lineSeparator());
      }
      sb.append("```java").append(System.lineSeparator());
      sb.append(toJava(name)).append(System.lineSeparator());
      sb.append("```").append(System.lineSeparator());
      sb.append(System.lineSeparator());
    }

    List<String> errors = new ArrayList<>();
    TuningRegistry.errors().forEach(e -> errors.add(e.summary()));
    if (!errors.isEmpty()) {
      sb.append("## Configuration problems").append(System.lineSeparator());
      sb.append(System.lineSeparator());
      for (String error : errors) {
        sb.append("- ").append(error).append(System.lineSeparator());
      }
    }
    return sb.toString();
  }

  /**
   * Write {@link #markdownReport()} beside the gains file.
   *
   * @return the path written, or empty on failure
   */
  public static Optional<Path> writeMarkdownReport() {
    PersistentStore store = PersistentStore.persistent();
    if (!store.writeText(kReportFileName, markdownReport())) {
      return Optional.empty();
    }
    return Optional.of(store.resolve(kReportFileName));
  }

  // ---- internals ---------------------------------------------------------------------------

  private static String field(String method, double value, String unit) {
    return String.format(
            Locale.ROOT, "         .%s(%s)      // %s", method, trim(value), unit)
        + System.lineSeparator();
  }

  private static String fieldLast(String method, double value, String unit) {
    return String.format(
            Locale.ROOT, "         .%s(%s);     // %s", method, trim(value), unit)
        + System.lineSeparator();
  }

  private static String unitLabel(TuningTarget target) {
    return target.siDomain().name().startsWith("LINEAR") ? "m" : "rad";
  }

  private static String trim(double value) {
    if (Double.isNaN(value)) {
      // NaN is what Gains.UNTUNED carries, and it must stay visible rather than becoming 0.0: an
      // untuned mechanism refuses closed-loop control, and a silently-zeroed kP would not.
      return "Double.NaN";
    }
    String rendered = String.format(Locale.ROOT, "%.6f", value);
    // Trim trailing zeros but always leave one decimal place: "42" would compile as an int literal
    // and read as a count rather than as a gain, and the whole point of the generated block is that
    // a student can see at a glance that every one of these numbers is a measured double.
    while (rendered.contains(".") && rendered.endsWith("0") && !rendered.endsWith(".0")) {
      rendered = rendered.substring(0, rendered.length() - 1);
    }
    return rendered;
  }

  private static String toConstantName(String mechanism) {
    StringBuilder sb = new StringBuilder(mechanism.length() + 4);
    for (int i = 0; i < mechanism.length(); i++) {
      char c = mechanism.charAt(i);
      if (Character.isUpperCase(c) && i > 0 && sb.charAt(sb.length() - 1) != '_') {
        sb.append('_');
      }
      sb.append(Character.toUpperCase(c));
    }
    return sb.toString();
  }
}

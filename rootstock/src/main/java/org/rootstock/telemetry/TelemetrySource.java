package org.rootstock.telemetry;

/**
 * Implemented by every Rootstock mechanism, drivetrain and vision camera.
 *
 * <p><b>REGISTRATION AND DECLARATION ONLY.</b> Both methods are called exactly once, from
 * {@code RootstockRegistry.addAll(...)} (D27), and never again. There is no per-cycle callback into
 * this interface and there is no {@code TelemetrySink}: values are <b>pushed</b> by the implementer
 * through the {@link RootstockLog} statics during its own {@code periodic()}.
 *
 * <p><b>Why push and not pull.</b> An earlier revision declared {@code void sample(TelemetrySink)}
 * here and had telemetry pull every mechanism's values once per cycle, while the mechanism base class
 * simultaneously pushed the same key block from its own {@code periodic()}. Those two cannot both
 * ship: either both paths publish — duplicate keys, doubled bytes, and a governor attributing the same
 * bytes twice — or the mechanisms do not implement this interface at all. D9 settles it by naming
 * {@code RootstockLog} "the single static facade every domain calls", and a facade every domain calls is
 * a push facade.
 *
 * <p>This interface is the <i>entire</i> coupling between the telemetry domain and the mechanism
 * domain. Telemetry does not know what an elevator is.
 */
public interface TelemetrySource {

  /**
   * A stable, unique, path-safe name. Becomes the log key segment: {@code "Rootstock/<name>/..."}.
   *
   * <p>Registering two sources with the same name is a fatal config error, not a warning: two
   * mechanisms writing one key block produce a log where every trace is the interleaving of two
   * mechanisms and nothing in it is true.
   *
   * @return the name, e.g. {@code "Elevator"} or {@code "Drive"}
   */
  String telemetryName();

  /**
   * Called once at registration. The mechanism describes its own schema.
   *
   * <p>Telemetry uses the result to (a) attach unit metadata so AdvantageScope labels and converts
   * correctly, (b) size the per-motor arrays for {@code ./gradlew logBudget}, (c) generate the Elastic
   * and AdvantageScope layouts, and (d) build the expected key set for the per-cycle schema audit —
   * which is what makes "zero user code produces the standard schema" a <i>checked</i> claim under the
   * push model rather than an architectural assertion.
   *
   * <p>The descriptor is constructed by telemetry and handed in here. An implementer must not store
   * it: it is valid only for the duration of this call, which is what keeps "consumed once, at
   * registration" structurally true rather than conventionally true.
   *
   * @param d the descriptor to declare into
   */
  void describe(TelemetryDescriptor d);
}

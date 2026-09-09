package org.rootstock.core.health;

/**
 * Anything that can report faults. Implemented by mechanisms, drive, vision, LEDs, the vendor
 * adapters, and the seven built-in monitors.
 *
 * <p>It is an <em>interface</em>, not a base class, on purpose: a team with hand-rolled subsystems
 * is served by exactly the same registration call as a team using {@code Mechanism}, because routing
 * is by interface and never by base class (DESIGN.md §8 rule 7).
 *
 * <p>You do not register this yourself. {@code RootstockRegistry.addAll(m_drive, m_elevator, m_arm)}
 * inspects each argument once and routes anything {@code instanceof HealthSource} to {@link
 * HealthMonitor}. That is D27: one registration call, not four parallel lists.
 */
public interface HealthSource {

  /**
   * A stable name for this source, used as the NetworkTables subtable ({@code
   * /Rootstock/Health/&lt;name&gt;/}), the {@link org.rootstock.core.alert.Alerts} group for mirrored
   * alerts, the round-robin slice name, and the key {@link HealthMonitor#expectAbsent(String,
   * String)} matches against.
   *
   * <p>Stable means stable across boots and across replays: registration is idempotent by this
   * name, and slice order is part of the replay contract.
   *
   * @return the name, e.g. {@code "Arm"}, {@code "Vision/front"}, {@code "CAN/rio"}
   */
  String healthName();

  /**
   * Report whatever is wrong right now.
   *
   * <p>Called by {@link HealthMonitor} <strong>at most ONCE PER ROBOT LOOP</strong>, and only on the
   * loop whose cycle index selects this source (round-robin — see the package javadoc). It is
   * <em>never</em> called for every source in the same loop, and it is <em>never</em> gated on a
   * wall clock, so the cycles on which it runs are identical in real time, in simulation, in replay
   * and in a JUnit test.
   *
   * <p>Contract for implementors: must not block, must not allocate per fault (use the collector's
   * helpers), and must complete in well under 1 ms. If your check genuinely costs more than that —
   * a device configuration read-back, say — declare {@code
   * HealthMonitor.watch(this).everyNSweeps(n)} rather than doing the work every sweep.
   *
   * @param out where to report faults; valid only for the duration of this call
   */
  void pollHealth(FaultCollector out);
}

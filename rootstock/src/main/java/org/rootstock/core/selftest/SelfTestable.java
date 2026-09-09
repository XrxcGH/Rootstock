package org.rootstock.core.selftest;

/**
 * Anything that can prove it works. Implemented by mechanisms, drive and vision.
 *
 * <p>An interface, not a base class: a team with hand-rolled subsystems opts in with two methods and
 * no inheritance (DESIGN.md §8 rule 7). 135's Consul required {@code extends SubsystemChecker}, which
 * is why almost nobody outside 135 ever used it.
 *
 * <p>You do not register this yourself — {@code RootstockRegistry.addAll(...)} routes anything {@code
 * instanceof SelfTestable} to {@link SelfTest}, and {@code .excludeFrom(Registry.SELFTEST)} is the
 * opt-out. Registering a bare named routine with no owning object <em>is</em> a public call: see
 * {@link SelfTest#register(String, java.util.function.Supplier)}.
 */
public interface SelfTestable {

  /**
   * A stable name for this target, used as the result key and the NT subtable {@code
   * /Rootstock/SelfTest/&lt;name&gt;/}.
   *
   * @return the name, e.g. {@code "Arm"}
   */
  String selfTestName();

  /**
   * Build the routine to run.
   *
   * <p>Called fresh at the start of every run, never cached, because a routine carries the mutable
   * state of one execution — the baselines an {@code expectMoved} compares against, the samples an
   * {@code expectCurrentBetween} accumulates. A cached routine would report the previous run's
   * observations.
   *
   * @return the routine; never null
   */
  SelfTestRoutine selfTestRoutine();
}

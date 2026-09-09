/**
 * On-robot system identification: the motion, the streaming fit, and the sample record it runs on.
 *
 * <p><b>What is reused and what is replaced.</b> The motion is WPILib's — {@code SysIdRoutine}'s
 * quasistatic ramp and dynamic step are correct, well tested, and re-implementing them would be
 * exactly the duplication this library refuses to do. What is replaced is the <em>analysis</em>, and
 * where it happens. SysId's published workflow is a nine-step, laptop-bound ritual that ends with a
 * student retyping four numbers into {@code Constants.java}; a small team does that once, badly, and
 * never repeats it. {@link org.rootstock.tuning.sysid.FeedforwardRegression} does the same fit on
 * the robot, in constant memory, while the sweep is still running.
 *
 * <p><b>What is added.</b> WPILib states plainly that its routine only creates voltage commands and
 * that limits are the team's problem. That is the gap {@link org.rootstock.control.TuningSupervisor}
 * fills, and {@link org.rootstock.tuning.sysid.SysIdSweep} is what wires the two together: the ramp
 * rate, the step voltage and the timeouts are derived from the mechanism's own safe band rather than
 * from WPILib's fixed 1 V/s, 7 V, 10 s defaults, which are dangerous on a short-travel elevator.
 *
 * <p>Nothing here writes voltage directly. Every volt goes through the supervisor.
 */
package org.rootstock.tuning.sysid;

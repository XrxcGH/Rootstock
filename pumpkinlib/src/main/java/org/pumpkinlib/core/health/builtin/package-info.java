/**
 * The seven built-in health monitors, on by default.
 *
 * <p>The vendor adapters are the easy half of competition-day health. These are the checks nobody
 * writes: every one is backed by a documented WPILib API that most teams have never heard of, and
 * every one of them corresponds to a way a robot sits still on the field while its drivers stare at
 * it.
 *
 * <p><strong>Seven types, eight slices, eleven conditions</strong> — the three numbers are stated
 * together, derived rather than typed, and asserted by {@code BuiltinMonitorCountTest}:
 *
 * <ul>
 *   <li><strong>7 types</strong> — {@link org.pumpkinlib.core.health.builtin.CanBusMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.BatteryMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.RailMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.BrownoutMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.DeployMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.LoopTimeMonitor}, {@link
 *       org.pumpkinlib.core.health.builtin.DsMonitor}. These are the only classes in this package,
 *       and that is on purpose.
 *   <li><strong>8 slices</strong> — six types register one slice each, {@code RailMonitor} registers
 *       three (5 V / 3.3 V / 6 V), and {@code LoopTimeMonitor} registers none because it is
 *       measuring loops and so runs every loop. 1+1+3+1+1+1+0 = 8.
 *   <li><strong>11 conditions</strong> — the rows of the {@code MatchImpact} table in {@code
 *       design/06} §8.5. Exactly four of them can reach the driver, and each of those four is
 *       something a human can act on in the ninety seconds before a match.
 * </ul>
 *
 * <p>Adding an eighth monitor type is therefore a four-document change plus a test edit, by
 * construction. That is the point, not an obstacle.
 *
 * <p>Every monitor here calls {@code HealthMonitor.watch(this).ownAlerts()}, because the impact
 * table is per <em>condition</em>: a battery resting below 12.3 V is only a {@code WARNING} yet it
 * blocks the match, while a latched brownout is an {@code ERROR} that is pit-only because the driver
 * cannot act on it. No severity-to-impact mapping expresses both, so these monitors raise their own
 * alerts and the generic mirror stands down.
 */
package org.pumpkinlib.core.health.builtin;

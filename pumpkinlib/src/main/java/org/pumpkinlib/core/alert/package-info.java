/**
 * The one alert facade for the whole library.
 *
 * <p>{@link org.pumpkinlib.core.alert.Alerts} creates them,
 * {@link org.pumpkinlib.core.alert.PumpkinAlert} is the handle,
 * {@link org.pumpkinlib.core.alert.AlertRegistry} answers "is the robot OK right now?", and
 * {@link org.pumpkinlib.core.alert.AlertBridge} turns rising edges into driver notifications.
 *
 * <p>Two orthogonal axes, and the second one is the reason this package exists:
 * {@link org.pumpkinlib.core.alert.Severity} says how bad it is, and
 * {@link org.pumpkinlib.core.alert.MatchImpact} says whether the robot should take the field. A
 * realistic robot has about a hundred alert sites and a normal half-built one raises seventeen at
 * once; without the second axis the panel becomes noise, and the one real alert on Saturday morning
 * is indistinguishable from the fifteen everyone has been scrolling past since January.
 *
 * <p>This package is a leaf: it depends on WPILib's {@code Alert} and {@code Trigger}, on
 * {@code core.compat.Clock}, and on nothing else in PumpkinLib. It never reads the driver-station
 * API — {@code MatchContext} is the library's single reader — and everything it schedules is counted
 * in loop cycles, so alerts behave identically in a real match, in simulation and in replay.
 */
package org.pumpkinlib.core.alert;

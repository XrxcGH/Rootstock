package org.rootstock.tuning;

import org.littletonrobotics.junction.LogTable;
import org.littletonrobotics.junction.inputs.LoggableInputs;

/**
 * Every tunable in the robot, as ONE logged input.
 *
 * <p>Two parallel arrays rather than a map, because {@code LogTable} stores primitives and arrays and
 * the pairing must be positional to round-trip. Keys are sorted once at first publish and never
 * reordered, so the wire layout is stable and a replay of an older log against newer code is
 * diagnosable rather than silently misaligned.
 *
 * <p>Hand-written rather than {@code @AutoLog} per D24: the annotation processor generates into the
 * annotated type's own package and derives key names from Java field names, which makes a field
 * rename a silent contract break.
 *
 * <p>Flags round-trip in their own pair of arrays rather than as {@code 0.0}/{@code 1.0} in the
 * doubles above: a boolean stored as a double is a boolean a replay can silently widen, and
 * AdvantageScope would render a pit kill switch as a number.
 */
final class TuningInputs implements LoggableInputs {

  /** Full NT keys of every registered double, e.g. {@code "/Tuning/Elevator/kP"}, sorted. */
  String[] keys = new String[0];

  /** The value of each key in {@link #keys}, positionally. */
  double[] values = new double[0];

  /** Full NT keys of every registered flag, e.g. {@code "/Tuning/Vision/camera0Enabled"}, sorted. */
  String[] flagKeys = new String[0];

  /** The value of each key in {@link #flagKeys}, positionally. */
  boolean[] flagValues = new boolean[0];

  @Override
  public void toLog(LogTable table) {
    table.put("Keys", keys);
    table.put("Values", values);
    table.put("FlagKeys", flagKeys);
    table.put("FlagValues", flagValues);
  }

  @Override
  public void fromLog(LogTable table) {
    keys = table.get("Keys", keys);
    values = table.get("Values", values);
    flagKeys = table.get("FlagKeys", flagKeys);
    flagValues = table.get("FlagValues", flagValues);
  }
}

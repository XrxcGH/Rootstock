package org.rootstock.control;

/**
 * The one and only place canonical volts-per-SI {@link Gains} become vendor units.
 *
 * <p>Everything upstream of a sink — the tuning wizard, the persisted store, the NetworkTables
 * schema, the exporter that writes numbers back into a team's source, and the team's source itself —
 * speaks volts per metre and volts per radian. Everything downstream speaks Phoenix
 * output-per-rotation or REVLib duty-cycle-per-rotation. Putting the conversion in exactly one
 * object per mechanism is what makes a gain a transferable physical quantity instead of a magic
 * number bound to one vendor's firmware.
 *
 * <p>The alternative is what teams do today, and it is visible in the user's own repositories:
 * {@code ModuleIOTalonFX.java} contains {@code config.Slot0.kV = SwerveConstants.DRIVE_kV * 2.0 *
 * Math.PI;} with a unit test pinning the invariant. Right instinct, wrong layer — the conversion
 * lives next to one motor instead of next to the concept, so the next mechanism repeats it, or
 * doesn't.
 *
 * <h2>The conversion, stated once so adapters have no room to guess</h2>
 *
 * <p>Let {@code U = }{@link #siUnitsPerMechanismRotation()} — metres of travel per drum-or-wheel
 * rotation for a linear axis, {@code 2*PI} for a rotary one — and assume the device's feedback is
 * configured so that one device "rotation" equals one mechanism rotation (Phoenix {@code
 * FeedbackConfigs.SensorToMechanismRatio}, REV {@code ClosedLoopConfig} position conversion factor):
 *
 * <table border="1">
 *   <caption>Canonical to vendor</caption>
 *   <tr><th>Canonical</th><th>Phoenix 6 {@code Slot0Configs}, voltage request</th>
 *       <th>REVLib, voltage-compensated at {@code Vnom}</th></tr>
 *   <tr><td>kP [V/SI]</td><td>{@code kP * U}</td><td>{@code kP * U / Vnom}</td></tr>
 *   <tr><td>kI [V/(SI*s)]</td><td>{@code kI * U}</td><td>{@code kI * U / Vnom}</td></tr>
 *   <tr><td>kD [V/(SI/s)]</td><td>{@code kD * U}</td><td>{@code kD * U / Vnom} (REV's derivative
 *       time base differs; report the approximation in {@link #describeConversion()})</td></tr>
 *   <tr><td>kS [V]</td><td>{@code kS} unchanged</td><td>{@code kS / Vnom}</td></tr>
 *   <tr><td>kV [V/(SI/s)]</td><td>{@code kV * U}</td><td>{@code kV * U / (60 * Vnom)} — REV velocity
 *       is RPM</td></tr>
 *   <tr><td>kA [V/(SI/s^2)]</td><td>{@code kA * U}</td><td>{@code kA * U / (60 * Vnom)}</td></tr>
 *   <tr><td>kG [V], {@link GravityMode#CONSTANT}</td><td>{@code kG}, {@code Elevator_Static}</td>
 *       <td>{@code kG / Vnom} into REV's {@code kG}</td></tr>
 *   <tr><td>kG [V], {@link GravityMode#COSINE}</td><td>{@code kG}, {@code Arm_Cosine}, plus a
 *       <b>negated</b> arm-position offset from the horizontal reference</td>
 *       <td>{@code kG / Vnom} into REV's {@code kCos} — REVLib has no offset field, so a non-zero
 *       horizontal reference is a fatal config error, not a silent inaccuracy</td></tr>
 * </table>
 *
 * <h2>What an implementation must do</h2>
 *
 * <ul>
 *   <li><b>Be idempotent.</b> {@code apply(g)} twice with the same {@code g} must perform no bus
 *       traffic. This runs while a slider is being dragged.
 *   <li><b>Not allocate on the no-change path.</b> Same reason.
 *   <li><b>Use the non-blocking apply path</b> — zero timeout, no read-back — never the blocking
 *       verified one. A blocking config write inside a robot loop is a loop overrun.
 *   <li><b>Return false rather than throwing</b> when the device rejects a configuration, and raise
 *       a pit-only warning alert. A tuning session that takes the robot down is worse than a tuning
 *       session that says "the device would not take that".
 * </ul>
 *
 * <p>A sink is constructed knowing the three things {@link Gains} deliberately does not carry
 * (D1a/D2a): the {@link GravityMode}, the SI-units-per-mechanism-rotation factor, and the horizontal
 * reference for cosine gravity. Vendor implementations ({@code Phoenix6GainSink}, {@code
 * RevGainSink}) live in the vendor adapter artifacts, never here — nothing in this package imports a
 * vendor type.
 */
public interface GainSink {

  /**
   * Convert these canonical gains to whatever the real loop wants, and apply them.
   *
   * <p>Must write through to wherever the loop <em>actually</em> runs: on-device slot configs when
   * {@link ControlLocation#runsOnMotor()}, the wpimath controller objects otherwise. A sink that
   * accepts a gain and does not write it is the exact failure this interface exists to delete — a
   * team's tuning slider moving next to a controller that ignores it.
   *
   * @param gains the gains to apply, in volts per SI unit
   * @return true if the device accepted them, or if nothing changed; false if the device rejected
   *     them (in which case the sink has already raised an alert)
   */
  boolean apply(Gains gains);

  /**
   * A human-readable account of the conversion this sink performs, dumped to the log at boot and
   * shown in the tuning UI.
   *
   * <p>This is a teaching surface, not a debug string. The useful form shows the geometry, then each
   * gain with its arithmetic, so a competition-day CSA — or a student at 11pm — can check the
   * numbers by hand:
   *
   * <pre>
   *   1 mechanism rotation = 0.2794 m of travel
   *       (22-tooth #25 sprocket: 22 x 0.250 in = 5.500 in of chain, x 2 cascade stages)
   *   kP 128.000 V/m      -&gt; Slot0.kP 35.7632  (V per mechanism rotation) = 128.000 x 0.2794
   * </pre>
   *
   * @return the description, or an empty string if this sink performs no conversion worth printing
   */
  default String describeConversion() {
    return "";
  }

  /**
   * Metres of travel per mechanism rotation for a linear axis, or radians per mechanism rotation
   * (that is, {@code 2*PI}) for a rotary one.
   *
   * <p>This single number converts every gain in the table above, which is why it is the only piece
   * of geometry a vendor sink needs. It is exactly {@code MechanismUnits.siPerOutputRotation()}; a
   * sink should be handed that value rather than recomputing it, because a sink that derives its own
   * geometry is a second source of truth for the number the whole library is built on.
   *
   * @return SI units of mechanism travel per mechanism rotation; defaults to {@code 2*PI}, correct
   *     for every rotary axis
   */
  default double siUnitsPerMechanismRotation() {
    return 2.0 * Math.PI;
  }
}

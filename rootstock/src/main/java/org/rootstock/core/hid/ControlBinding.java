package org.rootstock.core.hid;

import java.util.Optional;

/**
 * One named, inspectable control binding: <em>this control, in this mode, does this action</em>.
 *
 * <p><strong>Why this type exists.</strong> {@code new JoystickButton(board, 7).onTrue(cmd)} is
 * unreadable, un-printable and impossible to hand to a new operator. Elite teams maintain a physical
 * control-map poster by hand and it goes stale; small teams have one student who remembers. Naming
 * the binding once, at the binding site, produces the poster for free — see
 * {@link ControlMap#markdown()} and {@link ControlMap#writeTo(java.nio.file.Path)}, which is what
 * generates {@code CONTROLS.md}. A driver should be able to see what every button does without
 * reading Java; that is an explicit design goal, not a nicety.
 *
 * <p>This is a pure value type. It records what was bound; it does not hold the {@code Command} or
 * the {@code Trigger}, because a poster that keeps the robot's command graph alive is a memory leak
 * and a replay hazard. The command is captured by <em>name</em> only.
 *
 * @param role       the role of the controller this binding lives on, e.g. {@code "Driver"}.
 * @param mode       the mode this binding is gated to, or empty if the binding is always live in
 *                   every mode. Never {@code null}.
 * @param action     the human-readable action, e.g. {@code "Score L4"}. This is the text a driver
 *                   reads on the poster.
 * @param control    the human-readable control label, e.g. {@code "Y"} or {@code "Left bumper"}. If
 *                   the binding was registered through an overload that does not take a label this
 *                   is {@link #kUnlabelledControl}.
 * @param activation when the command runs relative to the control's edge.
 * @param command    the name of the bound command, captured at binding time.
 */
public record ControlBinding(
    String role,
    Optional<String> mode,
    String action,
    String control,
    ControlBinding.Activation activation,
    String command) {

  /**
   * The control label used when a binding is registered through an overload that does not take one.
   *
   * <p>A binding with this label still appears on the poster — a missing button label is a defect a
   * reader can see and fix, whereas an absent row is a defect nobody notices.
   */
  public static final String kUnlabelledControl = "(unlabelled)";

  /** The label printed in the mode column for a binding that is live in every mode. */
  public static final String kAllModes = "(all modes)";

  /**
   * Validates every component, because a binding with a blank action produces a poster row that
   * tells the driver nothing and is worse than no row at all.
   *
   * @throws IllegalArgumentException if any string component is blank.
   * @throws NullPointerException     if any component is {@code null}.
   */
  public ControlBinding {
    role = requireText(role, "role");
    action = requireText(action, "action");
    control = requireText(control, "control");
    command = requireText(command, "command");
    if (mode == null) {
      throw new NullPointerException(
          "ControlBinding.mode was null for action '"
              + action
              + "'. Expected Optional.empty() for an always-live binding or Optional.of(modeName) "
              + "for a moded one. Fix: pass Optional.empty(), never null - this library has no "
              + "nulls in public APIs.");
    }
    if (activation == null) {
      throw new NullPointerException(
          "ControlBinding.activation was null for action '"
              + action
              + "'. Expected one of ON_TRUE, WHILE_TRUE, TOGGLE_ON_TRUE.");
    }
    mode.ifPresent(m -> requireText(m, "mode"));
  }

  /**
   * The mode column for the poster.
   *
   * @return the mode name, or {@value #kAllModes} when this binding is live in every mode.
   */
  public String modeLabel() {
    return mode.orElse(kAllModes);
  }

  /**
   * Whether this binding is live in every mode.
   *
   * @return {@code true} if the binding was registered outside any {@code mode(...)} block.
   */
  public boolean isGlobal() {
    return mode.isEmpty();
  }

  /**
   * One-line human summary, for boot logs and for {@code ControlMap.describe()}.
   *
   * @return e.g. {@code "Driver/MANUAL  Y -> Elevator L4  (on press, command Elevator.goTo)"}.
   */
  public String describe() {
    return role
        + "/"
        + modeLabel()
        + "  "
        + control
        + " -> "
        + action
        + "  ("
        + activation.label()
        + ", command "
        + command
        + ")";
  }

  private static String requireText(String value, String field) {
    if (value == null) {
      throw new NullPointerException(
          "ControlBinding." + field + " was null. Fix: pass a non-blank name at the binding site.");
    }
    if (value.isBlank()) {
      throw new IllegalArgumentException(
          "ControlBinding."
              + field
              + " was blank. Expected a short human-readable name such as \"Score L4\". "
              + "Fix: name the binding at the call site - the name is what a driver reads on the "
              + "printed control map.");
    }
    return value;
  }

  /**
   * When the bound command runs, relative to the control's edge.
   *
   * <p>The three values are exactly WPILib's {@code Trigger.onTrue} / {@code whileTrue} /
   * {@code toggleOnTrue}; naming them here lets the poster say <em>while held</em> instead of making
   * a driver infer it.
   */
  public enum Activation {
    /** Runs once when the control is pressed. */
    ON_TRUE("on press"),
    /** Runs while the control is held, and is cancelled on release. */
    WHILE_TRUE("while held"),
    /** Starts on press, and the next press cancels it. */
    TOGGLE_ON_TRUE("toggle on press");

    private final String m_label;

    Activation(String label) {
      m_label = label;
    }

    /**
     * The phrase printed in the "When" column of the control-map poster.
     *
     * @return a short driver-readable phrase, e.g. {@code "while held"}.
     */
    public String label() {
      return m_label;
    }
  }
}

package org.pumpkinlib.core.hid;

import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.networktables.StringPublisher;
import edu.wpi.first.wpilibj2.command.Command;
import edu.wpi.first.wpilibj2.command.Commands;
import edu.wpi.first.wpilibj2.command.button.CommandGenericHID;
import edu.wpi.first.wpilibj2.command.button.Trigger;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Named, modal, printable button bindings.
 *
 * <p><strong>The problem.</strong> {@code new JoystickButton(board, 7).onTrue(cmd)} is unreadable
 * and un-printable. Every team ends up maintaining a control-map poster by hand, in a second place,
 * and it goes stale by week three. {@code ControlMap} makes the binding site the single source of
 * truth: name the action once and the poster ({@link #markdown()}, {@link #writeTo(Path)} →
 * {@code CONTROLS.md}) and the dashboard string ({@link #publish()} → {@code /Pumpkin/Controls}) are
 * generated from it. A driver can see what every button does without reading Java.
 *
 * <p><strong>Modes, and the mandatory {@code MANUAL} (decision D30).</strong> The rationale is from
 * the design dossier and it is not a style preference:
 *
 * <blockquote>
 * "Automation without a manual mode loses matches. A single-button macro that depends on vision will
 * fail when a tag is occluded by a defender, and if there is no fallback the robot is dead for the
 * match."
 * </blockquote>
 *
 * <p>Every binding registered inside a {@link #mode(String, Consumer)} block is automatically ANDed
 * with {@link #inMode(String)}, so the mode is declared once and no binding site repeats the
 * condition. It is a {@code Trigger.and()} wrapper, not a state machine.
 *
 * <p>There are two ways to satisfy D30, and the first is preferred:
 *
 * <ol>
 *   <li>{@link #withManual(String, CommandGenericHID, Consumer)} — {@code MANUAL} is a
 *       <em>required constructor argument</em>. It is structurally impossible to build such a map
 *       without a manual fallback; there is nothing to forget.
 *   <li>{@link #of(String, CommandGenericHID)} plus an explicit {@code .mode("MANUAL", ...)}. If a
 *       map declares modes and none of them is {@value #kManualMode}, {@link #publish()} reports a
 *       persistent defect through {@link #setDefectSink(Consumer)} (the alert domain installs
 *       {@code Alerts.error(...)} there at boot; the default prints a loud block to {@code stderr}).
 * </ol>
 *
 * <p><strong>Usage.</strong>
 *
 * <pre>{@code
 * ControlMap.of("Driver", m_driver)
 *     .modeSelector("Back", m_driver.back())
 *     .mode("AUTO", c -> c
 *         .onTrue("Score L4", "Y", x -> x.y(), m_super.request(SuperState.L4)))
 *     .mode("MANUAL", c -> c
 *         .onTrue("Elevator L4", "Y", x -> x.y(), ELEVATOR.goTo(kL4)))
 *     // Unmoded bindings are always live, in every mode.
 *     .onTrue("Home", "Start", c -> c.start(), ELEVATOR.homeCommand())
 *     .publish();
 * }</pre>
 *
 * <p><strong>Thread-safety.</strong> None, deliberately. Bindings are declared once from the robot
 * container constructor on the main thread; a lock here would buy nothing and cost loop time.
 *
 * @param <T> the command-based HID wrapper type, e.g. {@code CommandXboxController}. The binding
 *     lambdas receive it, so a binding site reads {@code x -> x.leftBumper()} rather than repeating
 *     the controller field name.
 */
public final class ControlMap<T extends CommandGenericHID> {

  /** The one mode name that D30 requires any modal control map to declare. */
  public static final String kManualMode = "MANUAL";

  /** NetworkTables topic carrying the generated markdown control map, for an Elastic text widget. */
  public static final String kControlsTopic = "/Pumpkin/Controls";

  /** NetworkTables topic carrying the currently active mode name, for the driver tab. */
  public static final String kModeTopic = "/Pumpkin/Driver/Mode";

  // Every map ever created, in declaration order. markdown() walks this so one call produces the
  // whole poster across Driver, Operator and any test board.
  private static final List<ControlMap<?>> s_maps = new ArrayList<>();

  // Defects found at publish() time, kept so a test or a health monitor can assert on them without
  // scraping stderr.
  private static final List<String> s_defects = new ArrayList<>();

  private static Consumer<String> s_defectSink = ControlMap::printDefect;

  private static StringPublisher s_controlsPublisher;
  private static StringPublisher s_modePublisher;

  private final String m_role;
  private final T m_hid;
  private final List<ControlBinding> m_bindings = new ArrayList<>();
  private final List<String> m_modes = new ArrayList<>();

  // Set only for the duration of a mode(...) block. Every binding registered while it is present is
  // ANDed with inMode(that name) and tagged with it on the poster.
  private Optional<String> m_declaringMode = Optional.empty();

  // Empty until someone actually switches modes; activeMode() then falls back to the default. This
  // is deliberately lazy so that declaration order and the MANUAL-first default resolve correctly
  // no matter what order the fluent chain is written in.
  private Optional<String> m_activeMode = Optional.empty();

  private Optional<String> m_modeSelectorControl = Optional.empty();

  private ControlMap(String role, T hid) {
    m_role = role;
    m_hid = hid;
  }

  // ---------------------------------------------------------------------------------------------
  // Construction
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates a control map for one role.
   *
   * <p>Prefer {@link #withManual(String, CommandGenericHID, Consumer)} when the map will have modes:
   * it makes the {@value #kManualMode} mode a required argument rather than something to remember.
   *
   * @param role the human-readable role of this controller, e.g. {@code "Driver"} or
   *     {@code "Operator"}. It is the section heading on the printed poster.
   * @param hid  the command-based HID wrapper, e.g. a {@code CommandXboxController}.
   * @param <T>  the HID wrapper type.
   * @return a new, empty control map, already registered for {@link #markdown()} and
   *     {@link #isPortRegistered(int)}.
   * @throws IllegalArgumentException if {@code role} is blank.
   * @throws NullPointerException     if {@code hid} is {@code null}.
   */
  public static <T extends CommandGenericHID> ControlMap<T> of(String role, T hid) {
    if (role == null || role.isBlank()) {
      throw new IllegalArgumentException(
          "ControlMap.of(role, hid) was given a blank role. Expected a short name such as "
              + "\"Driver\" or \"Operator\" - it becomes the section heading in CONTROLS.md. "
              + "Fix: ControlMap.of(\"Driver\", m_driver).");
    }
    if (hid == null) {
      throw new NullPointerException(
          "ControlMap.of(\""
              + role
              + "\", hid) was given a null HID. Fix: pass the CommandXboxController (or other "
              + "CommandGenericHID) field, e.g. ControlMap.of(\"Driver\", m_driver).");
    }
    ControlMap<T> map = new ControlMap<>(role, hid);
    s_maps.add(map);
    return map;
  }

  /**
   * Creates a control map whose {@value #kManualMode} mode is a <em>required argument</em>.
   *
   * <p>This is the D30-safe entry point. {@link #of(String, CommandGenericHID)} plus a forgotten
   * {@code .mode("MANUAL", ...)} is a defect that {@link #publish()} can only report after the fact;
   * this overload makes the defect unrepresentable. The manual bindings are registered first, so
   * {@value #kManualMode} is also the mode the robot boots into.
   *
   * @param role           the human-readable role of this controller.
   * @param hid            the command-based HID wrapper.
   * @param manualBindings the manual-fallback bindings. Called immediately with the new map, exactly
   *     as if you had written {@code .mode("MANUAL", manualBindings)}.
   * @param <T>            the HID wrapper type.
   * @return the new control map, with {@value #kManualMode} declared and populated.
   * @throws IllegalArgumentException if {@code role} is blank.
   * @throws NullPointerException     if {@code hid} or {@code manualBindings} is {@code null}.
   */
  public static <T extends CommandGenericHID> ControlMap<T> withManual(
      String role, T hid, Consumer<ControlMap<T>> manualBindings) {
    if (manualBindings == null) {
      throw new NullPointerException(
          "ControlMap.withManual(\""
              + role
              + "\", hid, manualBindings) was given null bindings. The whole point of this overload "
              + "is that the manual fallback cannot be omitted. Fix: pass the MANUAL block, e.g. "
              + "ControlMap.withManual(\"Driver\", m_driver, m -> m.whileTrue(...)).");
    }
    return ControlMap.<T>of(role, hid).mode(kManualMode, manualBindings);
  }

  // ---------------------------------------------------------------------------------------------
  // Bindings
  // ---------------------------------------------------------------------------------------------

  /**
   * Binds a command to run once when the control is pressed.
   *
   * <p>This is the design's three-argument spelling; the control label is
   * {@link ControlBinding#kUnlabelledControl} on the poster. Prefer
   * {@link #onTrue(String, String, Function, Command)} so the poster names the button.
   *
   * @param action the human-readable action, e.g. {@code "Score L4"}.
   * @param button extracts the trigger from this map's HID, e.g. {@code x -> x.y()}.
   * @param c      the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> onTrue(String action, Function<T, Trigger> button, Command c) {
    return onTrue(action, ControlBinding.kUnlabelledControl, button, c);
  }

  /**
   * Binds a command to run while the control is held, cancelling it on release.
   *
   * @param action the human-readable action.
   * @param button extracts the trigger from this map's HID.
   * @param c      the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> whileTrue(String action, Function<T, Trigger> button, Command c) {
    return whileTrue(action, ControlBinding.kUnlabelledControl, button, c);
  }

  /**
   * Binds a command to start on press, with the next press cancelling it.
   *
   * @param action the human-readable action.
   * @param button extracts the trigger from this map's HID.
   * @param c      the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> toggleOnTrue(String action, Function<T, Trigger> button, Command c) {
    return toggleOnTrue(action, ControlBinding.kUnlabelledControl, button, c);
  }

  /**
   * Binds a command to run once when the control is pressed, naming the control for the poster.
   *
   * @param action  the human-readable action, e.g. {@code "Score L4"}.
   * @param control the human-readable control label, e.g. {@code "Y"} or {@code "Left bumper"}.
   * @param button  extracts the trigger from this map's HID, e.g. {@code x -> x.y()}.
   * @param c       the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> onTrue(
      String action, String control, Function<T, Trigger> button, Command c) {
    return bind(action, control, button, c, ControlBinding.Activation.ON_TRUE);
  }

  /**
   * Binds a command to run while the control is held, naming the control for the poster.
   *
   * @param action  the human-readable action.
   * @param control the human-readable control label.
   * @param button  extracts the trigger from this map's HID.
   * @param c       the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> whileTrue(
      String action, String control, Function<T, Trigger> button, Command c) {
    return bind(action, control, button, c, ControlBinding.Activation.WHILE_TRUE);
  }

  /**
   * Binds a command to start on press and cancel on the next press, naming the control.
   *
   * @param action  the human-readable action.
   * @param control the human-readable control label.
   * @param button  extracts the trigger from this map's HID.
   * @param c       the command to run.
   * @return this map, for chaining.
   */
  public ControlMap<T> toggleOnTrue(
      String action, String control, Function<T, Trigger> button, Command c) {
    return bind(action, control, button, c, ControlBinding.Activation.TOGGLE_ON_TRUE);
  }

  private ControlMap<T> bind(
      String action,
      String control,
      Function<T, Trigger> button,
      Command c,
      ControlBinding.Activation activation) {
    if (button == null) {
      throw new NullPointerException(
          describeSite(action) + " was given a null button extractor. Fix: pass e.g. x -> x.y().");
    }
    if (c == null) {
      throw new NullPointerException(
          describeSite(action)
              + " was given a null command. Fix: pass the command to run; if you meant \"do "
              + "nothing\", delete the binding so it does not appear on the driver's poster.");
    }
    Trigger raw = button.apply(m_hid);
    if (raw == null) {
      throw new NullPointerException(
          describeSite(action)
              + " had a button extractor that returned null. Expected a Trigger, e.g. x -> x.y(). "
              + "Fix: return a trigger from the controller, not null.");
    }

    // The whole of modal control, right here: one Trigger.and(). No state machine.
    Trigger gated = m_declaringMode.isPresent() ? raw.and(inMode(m_declaringMode.get())) : raw;

    switch (activation) {
      case ON_TRUE -> gated.onTrue(c);
      case WHILE_TRUE -> gated.whileTrue(c);
      case TOGGLE_ON_TRUE -> gated.toggleOnTrue(c);
    }

    m_bindings.add(
        new ControlBinding(m_role, m_declaringMode, action, control, activation, commandName(c)));
    return this;
  }

  // ---------------------------------------------------------------------------------------------
  // Modal control (D30)
  // ---------------------------------------------------------------------------------------------

  /**
   * Declares a mode and registers every binding inside the block against it.
   *
   * <p>Every binding registered inside {@code bindings} is automatically ANDed with
   * {@link #inMode(String)}, so a mode is declared once and no binding site repeats the condition.
   *
   * <p><strong>At least one mode must be named {@value #kManualMode}.</strong> If a map declares
   * modes and none is {@value #kManualMode}, {@link #publish()} reports a persistent defect. The
   * rationale, from the dossier: <em>"Automation without a manual mode loses matches. A
   * single-button macro that depends on vision will fail when a tag is occluded by a defender, and
   * if there is no fallback the robot is dead for the match."</em>
   *
   * <p>Declaring the same mode name twice is allowed and simply adds more bindings to it — a team
   * splitting a long MANUAL block across two call sites is not making a mistake.
   *
   * @param name     the mode name, e.g. {@code "AUTO"} or {@value #kManualMode}.
   * @param bindings called immediately with this map; register bindings on it as usual.
   * @return this map, for chaining.
   * @throws IllegalArgumentException if {@code name} is blank, or if this call is nested inside
   *     another {@code mode(...)} block (modes do not nest — the resulting binding could never fire
   *     and that is a silent dead button).
   * @throws NullPointerException if {@code bindings} is {@code null}.
   */
  public ControlMap<T> mode(String name, Consumer<ControlMap<T>> bindings) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "ControlMap(\""
              + m_role
              + "\").mode(name, ...) was given a blank mode name. Expected e.g. \"MANUAL\" or "
              + "\"AUTO\". Fix: name the mode - it is published to "
              + kModeTopic
              + " and shown to the driver.");
    }
    if (bindings == null) {
      throw new NullPointerException(
          "ControlMap(\""
              + m_role
              + "\").mode(\""
              + name
              + "\", null): the bindings block was null. Fix: pass m -> m.onTrue(...).");
    }
    if (m_declaringMode.isPresent()) {
      throw new IllegalArgumentException(
          "ControlMap(\""
              + m_role
              + "\").mode(\""
              + name
              + "\") was called inside mode(\""
              + m_declaringMode.get()
              + "\"). Modes do not nest: a binding gated on two modes at once can never fire, which "
              + "is a dead button nobody notices until a match. Fix: close the outer block first "
              + "and declare the two modes side by side.");
    }
    if (!m_modes.contains(name)) {
      m_modes.add(name);
    }
    m_declaringMode = Optional.of(name);
    try {
      bindings.accept(this);
    } finally {
      // finally, so a throwing bindings block cannot leave every later binding silently gated on a
      // mode the author thought they had closed.
      m_declaringMode = Optional.empty();
    }
    return this;
  }

  /**
   * Declares the {@value #kManualMode} mode. Sugar for {@code mode(kManualMode, bindings)}.
   *
   * @param bindings called immediately with this map.
   * @return this map, for chaining.
   */
  public ControlMap<T> manualMode(Consumer<ControlMap<T>> bindings) {
    return mode(kManualMode, bindings);
  }

  /**
   * A trigger that is true while the named mode is selected.
   *
   * <p>Public so team code can gate its own logic on the same condition the bindings use, rather
   * than inventing a parallel boolean that can disagree.
   *
   * @param name the mode name.
   * @return a trigger on this map's active mode.
   * @throws IllegalArgumentException if {@code name} is blank, or if this map declares modes and
   *     {@code name} is not one of them (a trigger that can never be true is a dead button).
   */
  public Trigger inMode(String name) {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException(
          "ControlMap(\"" + m_role + "\").inMode(name) was given a blank mode name.");
    }
    if (!m_modes.isEmpty() && !m_modes.contains(name)) {
      throw new IllegalArgumentException(
          "ControlMap(\""
              + m_role
              + "\").inMode(\""
              + name
              + "\"): no such mode. Declared modes are "
              + m_modes
              + ". A trigger on an undeclared mode is never true, so the binding would be a dead "
              + "button. Fix: declare it with .mode(\""
              + name
              + "\", ...) or correct the spelling (mode names are case-sensitive).");
    }
    return new Trigger(() -> name.equals(activeMode()));
  }

  /**
   * Advances to the next declared mode on each rising edge of {@code next}.
   *
   * <p>The mode order is declaration order, and the selector wraps. It works while disabled, so a
   * driver can set the map up in the pit or on the field before the match starts.
   *
   * @param next the trigger that advances the mode, e.g. {@code m_driver.back()}.
   * @return this map, for chaining.
   * @throws NullPointerException if {@code next} is {@code null}.
   */
  public ControlMap<T> modeSelector(Trigger next) {
    return modeSelector(ControlBinding.kUnlabelledControl, next);
  }

  /**
   * Advances to the next declared mode on each rising edge, naming the control for the poster.
   *
   * @param control the human-readable control label, e.g. {@code "Back"}.
   * @param next    the trigger that advances the mode.
   * @return this map, for chaining.
   * @throws NullPointerException if {@code next} is {@code null}.
   */
  public ControlMap<T> modeSelector(String control, Trigger next) {
    if (next == null) {
      throw new NullPointerException(
          "ControlMap(\""
              + m_role
              + "\").modeSelector(trigger) was given a null trigger. Fix: pass e.g. "
              + "m_driver.back().");
    }
    m_modeSelectorControl = Optional.of(control);
    next.onTrue(
        Commands.runOnce(this::advanceMode)
            .ignoringDisable(true)
            .withName(m_role + "/NextControlMode"));
    return this;
  }

  /**
   * The mode that is live right now.
   *
   * <p>Before anything has switched modes this is the <em>default</em> mode:
   * {@value #kManualMode} when it is declared, otherwise the first declared mode. Booting into the
   * manual fallback is this implementation's choice where the design is silent, and it follows
   * directly from D30's rationale — the mode that always works should be the one that is live
   * before anybody has touched the selector.
   *
   * @return the active mode name, or {@code ""} if this map declares no modes at all.
   */
  public String activeMode() {
    return m_activeMode.orElseGet(this::defaultMode);
  }

  /**
   * Selects a mode directly. Mostly for tests and for team code that has its own selector UI.
   *
   * @param name the mode to select.
   * @throws IllegalArgumentException if {@code name} is not a declared mode.
   */
  public void selectMode(String name) {
    if (!m_modes.contains(name)) {
      throw new IllegalArgumentException(
          "ControlMap(\""
              + m_role
              + "\").selectMode(\""
              + name
              + "\"): no such mode. Declared modes are "
              + m_modes
              + ". Fix: declare it with .mode(...) first.");
    }
    m_activeMode = Optional.of(name);
    publishActiveMode();
  }

  private String defaultMode() {
    if (m_modes.contains(kManualMode)) {
      return kManualMode;
    }
    return m_modes.isEmpty() ? "" : m_modes.get(0);
  }

  private void advanceMode() {
    if (m_modes.isEmpty()) {
      defect(
          "ControlMap(\""
              + m_role
              + "\") has a modeSelector but declares no modes, so the button does nothing. "
              + "Fix: add .mode(\"MANUAL\", ...) (and any automated modes), or delete the "
              + "modeSelector call.");
      return;
    }
    int index = m_modes.indexOf(activeMode());
    m_activeMode = Optional.of(m_modes.get((index + 1) % m_modes.size()));
    publishActiveMode();
  }

  // ---------------------------------------------------------------------------------------------
  // Inspection
  // ---------------------------------------------------------------------------------------------

  /**
   * The HID this map binds against, for the rare call that needs the raw wrapper.
   *
   * @return the command-based HID wrapper passed to {@link #of(String, CommandGenericHID)}.
   */
  public T raw() {
    return m_hid;
  }

  /**
   * This map's role.
   *
   * @return the role name, e.g. {@code "Driver"}.
   */
  public String role() {
    return m_role;
  }

  /**
   * The Driver Station port this map's controller is plugged into.
   *
   * @return the USB port index reported by the underlying HID.
   */
  public int port() {
    return m_hid.getHID().getPort();
  }

  /**
   * Every binding on this map, in declaration order.
   *
   * @return an unmodifiable view; bindings are value objects and safe to keep.
   */
  public List<ControlBinding> bindings() {
    return Collections.unmodifiableList(m_bindings);
  }

  /**
   * Every mode this map declares, in declaration order.
   *
   * @return an unmodifiable view; empty if this map has no modes.
   */
  public List<String> modes() {
    return Collections.unmodifiableList(m_modes);
  }

  /**
   * The D30 defect for this map, if it has one.
   *
   * @return the defect text when this map declares modes and none is {@value #kManualMode},
   *     otherwise empty.
   */
  public Optional<String> manualModeDefect() {
    if (m_modes.isEmpty() || m_modes.contains(kManualMode)) {
      return Optional.empty();
    }
    return Optional.of(
        "ControlMap(\""
            + m_role
            + "\") declares modes "
            + m_modes
            + " but none of them is \""
            + kManualMode
            + "\". Automation without a manual mode loses matches: a single-button macro that "
            + "depends on vision will fail when a tag is occluded by a defender, and with no "
            + "fallback the robot is dead for the match. "
            + "Fix: add .mode(\""
            + kManualMode
            + "\", m -> ...) with direct, sensor-free controls for every automated action - or "
            + "build the map with ControlMap.withManual(\""
            + m_role
            + "\", hid, m -> ...), which makes the manual block a required argument.");
  }

  /**
   * A compact, one-line-per-binding dump, for boot logs.
   *
   * @return a multi-line human-readable description of this map.
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append("ControlMap[")
        .append(m_role)
        .append("] port ")
        .append(port())
        .append(", ")
        .append(m_bindings.size())
        .append(" bindings, modes ")
        .append(m_modes.isEmpty() ? "(none)" : m_modes.toString())
        .append(", active ")
        .append(activeMode().isEmpty() ? "(n/a)" : activeMode());
    for (ControlBinding b : m_bindings) {
      sb.append(System.lineSeparator()).append("  ").append(b.describe());
    }
    return sb.toString();
  }

  // ---------------------------------------------------------------------------------------------
  // Static registry and output
  // ---------------------------------------------------------------------------------------------

  /**
   * Every control map declared in this JVM, in declaration order.
   *
   * @return an unmodifiable view.
   */
  public static List<ControlMap<?>> all() {
    return Collections.unmodifiableList(s_maps);
  }

  /**
   * Every binding across every map, in declaration order.
   *
   * @return a fresh list of value objects.
   */
  public static List<ControlBinding> allBindings() {
    List<ControlBinding> out = new ArrayList<>();
    for (ControlMap<?> map : s_maps) {
      out.addAll(map.m_bindings);
    }
    return out;
  }

  /**
   * Whether any declared control map uses the given Driver Station port.
   *
   * <p>This exists for the tuning domain: {@code TuningWizard} refuses to share a controller with
   * the driver without an explicit, logged acknowledgement, because sharing one gamepad between
   * "drive the robot" and "authorize raw voltage to an arm" is a safety architecture error, not a
   * binding conflict.
   *
   * @param port the USB port index.
   * @return {@code true} if a control map already binds a controller on that port.
   */
  public static boolean isPortRegistered(int port) {
    for (ControlMap<?> map : s_maps) {
      if (map.port() == port) {
        return true;
      }
    }
    return false;
  }

  /**
   * The whole control map as markdown: one table per role, one section per mode.
   *
   * <p>This is what a gradle task writes into {@code CONTROLS.md} and what {@link #publish()} pushes
   * to {@value #kControlsTopic} for an Elastic text widget. It is deliberately plain markdown so it
   * prints on a pit poster.
   *
   * @return the generated document; never {@code null}, and non-empty even with no bindings so a
   *     team can see the file appear on day one.
   */
  public static String markdown() {
    StringBuilder sb = new StringBuilder();
    sb.append("# Controls").append('\n').append('\n');
    sb.append("_Generated from ControlMap. Do not edit by hand — edit the bindings._")
        .append('\n');

    if (s_maps.isEmpty()) {
      sb.append('\n')
          .append("No control maps have been declared. ")
          .append("Fix: build one with ControlMap.withManual(\"Driver\", m_driver, m -> ...).")
          .append('\n');
      return sb.toString();
    }

    for (ControlMap<?> map : s_maps) {
      sb.append('\n')
          .append("## ")
          .append(map.m_role)
          .append(" — port ")
          .append(map.port())
          .append('\n')
          .append('\n');

      if (!map.m_modes.isEmpty()) {
        sb.append("Modes: ")
            .append(String.join(", ", map.m_modes))
            .append(". Active at boot: ")
            .append(map.defaultMode())
            .append(". Mode selector: ")
            .append(map.m_modeSelectorControl.orElse("(none — modes cannot be changed from the "
                + "controller)"))
            .append('.')
            .append('\n');
        map.manualModeDefect()
            .ifPresent(d -> sb.append('\n').append("> **DEFECT (D30):** ").append(d).append('\n'));
      }

      appendSection(sb, "Always live (all modes)", map.m_bindings.stream().filter(
          ControlBinding::isGlobal).toList());
      for (String mode : map.m_modes) {
        appendSection(
            sb,
            "Mode: " + mode,
            map.m_bindings.stream().filter(b -> b.mode().filter(mode::equals).isPresent()).toList());
      }
    }
    return sb.toString();
  }

  private static void appendSection(StringBuilder sb, String heading, List<ControlBinding> rows) {
    if (rows.isEmpty()) {
      return;
    }
    sb.append('\n').append("### ").append(heading).append('\n').append('\n');
    sb.append("| Control | Action | When | Command |").append('\n');
    sb.append("| --- | --- | --- | --- |").append('\n');
    for (ControlBinding b : rows) {
      sb.append("| ")
          .append(escape(b.control()))
          .append(" | ")
          .append(escape(b.action()))
          .append(" | ")
          .append(escape(b.activation().label()))
          .append(" | ")
          .append(escape(b.command()))
          .append(" |")
          .append('\n');
    }
  }

  // A literal pipe would break the markdown table and a newline would break the row; both are
  // possible in a command name. Chosen here rather than validated away, because a slightly odd
  // command name is not worth refusing a binding over.
  private static String escape(String text) {
    return text.replace("|", "\\|").replace("\n", " ").replace("\r", " ");
  }

  /**
   * Finishes this map: checks the D30 manual-mode requirement and publishes to NetworkTables.
   *
   * <p>This is the last call in the fluent chain. It pushes {@link #markdown()} to
   * {@value #kControlsTopic} and the active mode name to {@value #kModeTopic}, and the mode is
   * re-published on every mode change afterwards — there is no periodic cost, the mode topic is
   * edge-driven.
   *
   * <p>If <em>this</em> map declares modes and none of them is {@value #kManualMode}, the defect is
   * reported through {@link #setDefectSink(Consumer)}. This is a persistent condition, not a
   * one-shot warning: the map is wrong until someone changes it.
   *
   * <p>The published markdown always covers <em>every</em> declared map, because the poster is one
   * document across Driver, Operator and any test board. Use {@link #publishAll()} to check every
   * map's D30 status at once.
   *
   * <p>When several maps declare modes, {@value #kModeTopic} carries the first one's, because the
   * design names exactly one driver-mode topic. Every map's mode is still visible in the markdown.
   */
  public void publish() {
    manualModeDefect().ifPresent(ControlMap::defect);
    controlsPublisher().set(markdown());
    publishActiveModeStatic();
  }

  /**
   * Publishes every declared map and checks all of them against D30.
   *
   * <p>For a boot sequence that wants one call after the whole robot container is built, rather than
   * one {@link #publish()} per map.
   */
  public static void publishAll() {
    for (ControlMap<?> map : s_maps) {
      map.manualModeDefect().ifPresent(ControlMap::defect);
    }
    controlsPublisher().set(markdown());
    publishActiveModeStatic();
  }

  /**
   * Prints the whole control map to the console, so a control-map poster exists in the boot log even
   * on a robot with no dashboard attached.
   */
  public static void printSummary() {
    System.out.println(markdown());
  }

  /**
   * Writes {@link #markdown()} to a file, creating parent directories as needed.
   *
   * <p>This is what the {@code CONTROLS.md} gradle task calls.
   *
   * @param file the destination, e.g. {@code Path.of("CONTROLS.md")}.
   * @throws UncheckedIOException if the file cannot be written. Deliberately not swallowed: a build
   *     task that silently fails to regenerate the poster is how the poster goes stale.
   * @throws NullPointerException if {@code file} is {@code null}.
   */
  public static void writeTo(Path file) {
    if (file == null) {
      throw new NullPointerException(
          "ControlMap.writeTo(null). Fix: pass a path, e.g. Path.of(\"CONTROLS.md\").");
    }
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(file, markdown(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(
          "ControlMap.writeTo could not write the control map to "
              + file.toAbsolutePath()
              + ". Fix: check that the directory exists and is writable.",
          e);
    }
  }

  /**
   * Installs the sink that receives persistent control-map defects, such as D30's missing
   * {@value #kManualMode} mode.
   *
   * <p>The alert domain installs {@code Alerts.error(...)} here at boot. The default prints a
   * clearly delimited block to {@code stderr}, so the defect is still impossible to miss in a
   * project that has not wired alerts up.
   *
   * @param sink receives one defect message per problem found.
   * @throws NullPointerException if {@code sink} is {@code null}.
   */
  public static void setDefectSink(Consumer<String> sink) {
    if (sink == null) {
      throw new NullPointerException(
          "ControlMap.setDefectSink(null). Fix: pass a consumer, or call setDefectSink("
              + "System.err::println) to restore printing.");
    }
    s_defectSink = sink;
  }

  /**
   * Every defect reported so far, de-duplicated in report order.
   *
   * <p>Exposed so a test or a health monitor can assert on the D30 condition without scraping the
   * console.
   *
   * @return a fresh list of defect messages.
   */
  public static List<String> defects() {
    return List.copyOf(new LinkedHashSet<>(s_defects));
  }

  /**
   * Forgets every declared map, binding, defect and publisher.
   *
   * <p>For tests only. The registry is static because {@link #markdown()} has to produce one poster
   * across every role, which means a test that declares maps would otherwise leak into the next.
   */
  public static void resetForTest() {
    s_maps.clear();
    s_defects.clear();
    s_defectSink = ControlMap::printDefect;
    if (s_controlsPublisher != null) {
      s_controlsPublisher.close();
      s_controlsPublisher = null;
    }
    if (s_modePublisher != null) {
      s_modePublisher.close();
      s_modePublisher = null;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Internals
  // ---------------------------------------------------------------------------------------------

  private void publishActiveMode() {
    publishActiveModeStatic();
  }

  private static void publishActiveModeStatic() {
    for (ControlMap<?> map : s_maps) {
      if (!map.m_modes.isEmpty()) {
        modePublisher().set(map.activeMode());
        return;
      }
    }
  }

  private static StringPublisher controlsPublisher() {
    if (s_controlsPublisher == null) {
      s_controlsPublisher =
          NetworkTableInstance.getDefault().getStringTopic(kControlsTopic).publish();
    }
    return s_controlsPublisher;
  }

  private static StringPublisher modePublisher() {
    if (s_modePublisher == null) {
      s_modePublisher = NetworkTableInstance.getDefault().getStringTopic(kModeTopic).publish();
    }
    return s_modePublisher;
  }

  private static void defect(String message) {
    if (s_defects.contains(message)) {
      return;
    }
    s_defects.add(message);
    s_defectSink.accept(message);
  }

  private static void printDefect(String message) {
    System.err.println(
        String.format(
            Locale.ROOT,
            "%n"
                + "==================== PumpkinLib CONTROL MAP DEFECT ====================%n"
                + "%s%n"
                + "======================================================================%n",
            message));
  }

  private String describeSite(String action) {
    return "ControlMap(\""
        + m_role
        + "\") binding \""
        + action
        + "\""
        + m_declaringMode.map(m -> " in mode \"" + m + "\"").orElse("");
  }

  private static String commandName(Command c) {
    String name = c.getName();
    return name == null || name.isBlank() ? c.getClass().getSimpleName() : name;
  }
}

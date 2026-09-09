package org.rootstock.core.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The robot-wide "two devices are fighting over CAN ID 22" check.
 *
 * <p>Duplicate CAN IDs are a classic lost afternoon: nothing throws, nothing logs, one motor simply
 * does not respond or two motors fight each other and cook a gearbox. The device that reports the
 * conflict is the CAN bus, and it reports it by being silent. This class turns that into a named
 * error that says <b>which two devices</b>, by the names the team gave them, and where each one was
 * declared.
 *
 * <p>Out-of-range IDs are caught here too, because they are the same lost afternoon with a worse
 * ending: {@code 9143-2025-A-Updated}'s {@code Constants.java} documents a CAN ID of 64 that
 * <i>crashed robot code on boot</i>, because Phoenix device IDs stop at 62. That must be a
 * collected, named error that still lets the robot boot into SAFE_MODE — never a stack trace.
 *
 * <h2>Why this class is stateless</h2>
 *
 * <p>The obvious implementation is a real registry: every {@code MotorSpec} registers its ID in its
 * constructor and the second registration throws. <b>That implementation is wrong</b>, and
 * {@code design/01} §5.6b explains why: Rootstock config objects are immutable records, so every
 * {@code withX()} copy re-runs the compact constructor. The per-robot overlay pattern
 * ({@code design/06} §7.3) — the design's own answer to sibling robots — would register CAN ID 22
 * twice while building one perfectly correct config, and report a conflict that does not exist.
 *
 * <p>So there is no global state here at all. The scan runs <b>once, globally</b>, over the final
 * resolved config set, from {@code RootstockRegistry.addAll(...)}:
 *
 * <pre>{@code
 * all.addAll(CanIdRegistry.scanForConflicts(resolvedSpecs(registrables)));   // ONE global scan
 * }</pre>
 *
 * <h2>What it returns</h2>
 *
 * <p>{@link Conflict} is a self-contained value with a fully rendered {@link Conflict#describe()}
 * message. {@code RootstockRegistry} maps each one to a {@code FATAL org.rootstock.config.ConfigError}
 * in one line. It is not a {@code ConfigError} directly because ArchUnit rule 9 keeps arrows out of
 * {@code org.rootstock.core}, and because a check that can be run from a unit test with three
 * hand-built records is worth more than one that needs the mechanism domain on the classpath.
 */
public final class CanIdRegistry {

  /** Lowest legal CAN device ID. */
  public static final int kMinDeviceId = 0;

  /**
   * Highest legal CAN device ID.
   *
   * <p>62 for both Phoenix 6 and REVLib. IDs 63 and above are reserved by the CAN addressing scheme
   * and constructing a device with one is the boot crash described above.
   */
  public static final int kMaxDeviceId = 62;

  /** The default CAN bus name, used when a device does not name one. */
  public static final String kDefaultBus = "rio";

  /** The sentinel {@link Device#declaredAt()} value meaning "no source location was recorded". */
  public static final String kUnknownLocation = "";

  private CanIdRegistry() {}

  /**
   * One CAN device as the scan sees it.
   *
   * @param owner the mechanism or subsystem that declares it, for example {@code "Arm"}
   * @param role its role within that owner, for example {@code "leader"}, {@code "follower 1"} or
   *     {@code "absolute encoder"}
   * @param deviceType the hardware type as a plain string, for example {@code "TalonFX"} — a string
   *     and not an enum precisely so adding a vendor never means editing this class
   * @param deviceId the CAN device ID
   * @param bus the CAN bus name; blank is normalised to {@value #kDefaultBus}
   * @param declaredAt where the team declared it, for example {@code "RobotConfig.java:76"}, or
   *     {@value #kUnknownLocation} if unknown
   */
  public record Device(
      String owner, String role, String deviceType, int deviceId, String bus, String declaredAt) {

    /** Canonical constructor; normalises the bus name and the null-ish string components. */
    public Device {
      owner = blankTo(owner, "(unnamed)");
      role = blankTo(role, "device");
      deviceType = blankTo(deviceType, "unknown device");
      bus = blankTo(bus, kDefaultBus);
      declaredAt = declaredAt == null ? kUnknownLocation : declaredAt.trim();
    }

    /**
     * A device on the default {@value #kDefaultBus} bus with no recorded source location.
     *
     * @param owner the mechanism that declares it
     * @param role its role within that mechanism
     * @param deviceType the hardware type
     * @param deviceId the CAN device ID
     * @return the device
     */
    public static Device of(String owner, String role, String deviceType, int deviceId) {
      return new Device(owner, role, deviceType, deviceId, kDefaultBus, kUnknownLocation);
    }

    /**
     * A device on a named bus with no recorded source location.
     *
     * @param owner the mechanism that declares it
     * @param role its role within that mechanism
     * @param deviceType the hardware type
     * @param deviceId the CAN device ID
     * @param bus the CAN bus name
     * @return the device
     */
    public static Device on(
        String owner, String role, String deviceType, int deviceId, String bus) {
      return new Device(owner, role, deviceType, deviceId, bus, kUnknownLocation);
    }

    /**
     * A copy of this device with a source location attached.
     *
     * @param location for example {@code "RobotConfig.java:76"}
     * @return a copy carrying that location
     */
    public Device declaredAt(String location) {
      return new Device(owner, role, deviceType, deviceId, bus, location);
    }

    /**
     * Whether this device's ID is addressable at all.
     *
     * @return true if the ID is within {@value #kMinDeviceId}..{@value #kMaxDeviceId}
     */
    public boolean idIsLegal() {
      return deviceId >= kMinDeviceId && deviceId <= kMaxDeviceId;
    }

    /**
     * One line of a conflict report.
     *
     * @return for example {@code "Arm" leader (TalonFX) declared at RobotConfig.java:76}
     */
    public String describe() {
      String head = '"' + owner + "\" " + role;
      return head
          + " ("
          + deviceType
          + ")"
          + (declaredAt.isEmpty() ? "" : " declared at " + declaredAt);
    }

    private static String blankTo(String value, String fallback) {
      return value == null || value.isBlank() ? fallback : value.trim();
    }
  }

  /** What kind of problem the scan found. */
  public enum Kind {
    /** Two or more devices claim the same ID on the same bus. They will fight. */
    DUPLICATE_ID,

    /** An ID outside {@value #kMinDeviceId}..{@value #kMaxDeviceId}. The device cannot be found. */
    ID_OUT_OF_RANGE
  }

  /**
   * One problem the scan found.
   *
   * @param kind duplicate or out of range
   * @param bus the CAN bus the problem is on
   * @param deviceId the offending device ID
   * @param devices every device involved, in the order they were scanned; two or more for a
   *     duplicate, exactly one for an out-of-range ID
   */
  public record Conflict(Kind kind, String bus, int deviceId, List<Device> devices) {

    /** Canonical constructor; defensively copies {@code devices}. */
    public Conflict {
      devices = List.copyOf(devices);
    }

    /**
     * A short one-line form, for a log topic or an alert title.
     *
     * @return for example {@code CAN ID 22 on bus "rio" is claimed by "Arm" and "Intake"}
     */
    public String summary() {
      if (kind == Kind.ID_OUT_OF_RANGE) {
        return "CAN ID "
            + deviceId
            + " on bus \""
            + bus
            + "\" is out of range ("
            + kMinDeviceId
            + ".."
            + kMaxDeviceId
            + "): "
            + devices.get(0).describe();
      }
      List<String> owners = new ArrayList<>();
      for (Device d : devices) {
        owners.add('"' + d.owner() + '"');
      }
      return "CAN ID "
          + deviceId
          + " on bus \""
          + bus
          + "\" is claimed by "
          + String.join(" and ", owners);
    }

    /**
     * The full message, in the shape {@code design/01} §5.6b specifies.
     *
     * <p>It names the ID, the bus, every device that claims it by the team's own name, where each
     * was declared, what physically goes wrong, and the fix — including the half students forget,
     * which is that renumbering in Phoenix Tuner X <b>and</b> in the code are two separate edits.
     *
     * @return the multi-line message
     */
    public String describe() {
      StringBuilder sb = new StringBuilder();
      if (kind == Kind.ID_OUT_OF_RANGE) {
        Device d = devices.get(0);
        sb.append("Rootstock CAN ID out of range\n\n");
        sb.append("  device id ")
            .append(deviceId)
            .append(" on bus \"")
            .append(bus)
            .append("\" is declared by:\n");
        sb.append("    - ").append(d.describe()).append('\n');
        sb.append("\n  CAN device IDs must be ")
            .append(kMinDeviceId)
            .append("..")
            .append(kMaxDeviceId)
            .append("; Phoenix 6 and REVLib both stop at ")
            .append(kMaxDeviceId)
            .append(". Constructing a device\n")
            .append("  with id ")
            .append(deviceId)
            .append(" does not fail politely: it crashes robot code on boot.\n")
            .append("  Renumber the device in Phoenix Tuner X / the REV Hardware Client AND here.");
        return sb.toString();
      }

      sb.append("Rootstock CAN ID conflict\n\n");
      sb.append("  device id ")
          .append(deviceId)
          .append(" on bus \"")
          .append(bus)
          .append("\" is claimed by ")
          .append(devices.size() == 2 ? "BOTH" : "ALL " + devices.size() + " OF")
          .append(":\n");

      int headWidth = 0;
      for (Device d : devices) {
        headWidth = Math.max(headWidth, ('"' + d.owner() + "\" " + d.role()).length());
      }
      int typeWidth = 0;
      for (Device d : devices) {
        typeWidth = Math.max(typeWidth, d.deviceType().length() + 2);
      }
      for (Device d : devices) {
        String head = '"' + d.owner() + "\" " + d.role();
        String type = "(" + d.deviceType() + ")";
        sb.append("    - ")
            .append(pad(head, headWidth))
            .append("  ")
            .append(pad(type, typeWidth))
            .append("  ")
            .append(d.declaredAt().isEmpty() ? "" : "declared at " + d.declaredAt())
            .append('\n');
      }
      sb.append(
          "\n  Two devices with the same ID on the same bus will fight: one of them silently does"
              + " nothing,\n"
              + "  or both drive the same mechanism against each other and destroy the gearbox."
              + " Renumber one\n"
              + "  in Phoenix Tuner X / the REV Hardware Client AND here.");
      return sb.toString();
    }

    private static String pad(String value, int width) {
      if (value.length() >= width) {
        return value;
      }
      return value + " ".repeat(width - value.length());
    }
  }

  /**
   * Scans the final resolved device set for duplicate and out-of-range CAN IDs.
   *
   * <p>Call this <b>once</b>, from {@code RootstockRegistry.addAll(...)}, over the fully resolved
   * config set — never from a record constructor. See the class javadoc for why.
   *
   * <p>Devices are keyed by {@code (bus, id)}: the same ID on two different CAN buses is legal and
   * common on a robot with a CANivore, and reporting it would train students to ignore this check.
   *
   * <p>Never throws. A null collection scans as empty, and a null element is skipped.
   *
   * @param devices every CAN device on the robot, from every mechanism, drive module and sensor
   * @return every problem found, duplicates first (in ascending bus/id order), then out-of-range
   *     IDs; empty when the robot's CAN map is sound
   */
  public static List<Conflict> scanForConflicts(Collection<Device> devices) {
    List<Conflict> duplicates = new ArrayList<>();
    List<Conflict> outOfRange = new ArrayList<>();
    if (devices == null || devices.isEmpty()) {
      return List.of();
    }

    // LinkedHashMap so the report order follows declaration order, which is the order the student
    // reads their own Constants file in.
    Map<String, List<Device>> byBusAndId = new LinkedHashMap<>();
    for (Device device : devices) {
      if (device == null) {
        continue;
      }
      if (!device.idIsLegal()) {
        outOfRange.add(
            new Conflict(
                Kind.ID_OUT_OF_RANGE, device.bus(), device.deviceId(), List.of(device)));
        // An out-of-range id still participates in the duplicate scan: two devices both wrongly
        // set to 64 are two separate problems and the team should see both.
      }
      byBusAndId
          .computeIfAbsent(device.bus() + '\0' + device.deviceId(), k -> new ArrayList<>())
          .add(device);
    }

    for (List<Device> group : byBusAndId.values()) {
      if (group.size() > 1) {
        Device first = group.get(0);
        duplicates.add(
            new Conflict(Kind.DUPLICATE_ID, first.bus(), first.deviceId(), group));
      }
    }

    List<Conflict> out = new ArrayList<>(duplicates.size() + outOfRange.size());
    out.addAll(duplicates);
    out.addAll(outOfRange);
    return List.copyOf(out);
  }

  /**
   * Renders a scan result as one string per problem, ready for the console and for a
   * {@code String[]} log topic.
   *
   * @param conflicts the scan result
   * @return one fully rendered message per conflict, in the same order
   */
  public static List<String> toStrings(Collection<Conflict> conflicts) {
    if (conflicts == null || conflicts.isEmpty()) {
      return List.of();
    }
    List<String> out = new ArrayList<>(conflicts.size());
    for (Conflict conflict : conflicts) {
      out.add(conflict.describe());
    }
    return List.copyOf(out);
  }

  /**
   * A human summary of a robot's CAN map: how many devices, on how many buses, and any problems.
   *
   * @param devices the same collection handed to {@link #scanForConflicts(Collection)}
   * @return a multi-line description for the boot dump and {@code rootstock doctor}
   */
  public static String describe(Collection<Device> devices) {
    if (devices == null || devices.isEmpty()) {
      return "CAN map: no devices declared.";
    }
    Map<String, List<Device>> byBus = new LinkedHashMap<>();
    for (Device device : devices) {
      if (device != null) {
        byBus.computeIfAbsent(device.bus(), k -> new ArrayList<>()).add(device);
      }
    }
    StringBuilder sb = new StringBuilder("CAN map: ");
    sb.append(devices.size()).append(" device(s) on ").append(byBus.size()).append(" bus(es)");
    for (Map.Entry<String, List<Device>> entry : byBus.entrySet()) {
      sb.append("\n  bus \"").append(entry.getKey()).append("\":");
      List<Device> sorted = new ArrayList<>(entry.getValue());
      sorted.sort((a, b) -> Integer.compare(a.deviceId(), b.deviceId()));
      for (Device device : sorted) {
        sb.append("\n    ")
            .append(String.format("%2d", device.deviceId()))
            .append("  ")
            .append(device.describe());
      }
    }
    List<Conflict> conflicts = scanForConflicts(devices);
    if (conflicts.isEmpty()) {
      sb.append("\n  no duplicate or out-of-range IDs.");
    } else {
      for (Conflict conflict : conflicts) {
        sb.append("\n  PROBLEM: ").append(conflict.summary());
      }
    }
    return sb.toString();
  }
}

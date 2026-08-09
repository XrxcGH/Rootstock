package org.pumpkinlib.testsupport;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;

/**
 * Installs do-nothing NetworkTables publishers into a class's private static publisher fields, so a
 * unit test can exercise a code path that publishes without WPILib's JNI natives present.
 *
 * <p><strong>Why this is necessary and not merely convenient.</strong> Several PumpkinLib classes
 * publish to NetworkTables as a courtesy and guard the call with {@code catch (RuntimeException)} —
 * {@code SliceScheduler.publish()} says so in as many words: <em>"NetworkTables is unavailable (a
 * pure-JVM unit test, say). Scheduling is the job; publishing is the courtesy. Never let the
 * courtesy break the job."</em> That guard does not work. On a JVM with no WPILib natives,
 * {@code NetworkTableInstance.getDefault()} does not throw a {@code RuntimeException}: WPILib's
 * native loader prints to stderr and calls {@code System.exit(1)}. No catch clause can intercept
 * that, and the whole test JVM dies with "finished with non-zero exit value 1" and no stack trace
 * naming the test that did it.
 *
 * <p>So the guard cannot be relied on, and the test must ensure the NT call is never reached at
 * all. Every publishing site in the library follows the same shape:
 *
 * <pre>{@code
 * if (m_publisher == null) {
 *   m_publisher = NetworkTableInstance.getDefault().getIntegerTopic(topic).publish();
 * }
 * m_publisher.set(value);
 * }</pre>
 *
 * <p>Pre-seeding the field with a non-null stub short-circuits the {@code null} check, so
 * {@code getDefault()} is never called and {@code set(...)} lands on the stub. The publisher types
 * ({@code IntegerPublisher}, {@code StringPublisher}, …) are <em>interfaces</em>, and loading an
 * interface does not run any native static initialiser — verified — so a {@link Proxy} over the
 * field's own declared type is safe.
 *
 * <p>The alternative was tagging every such test {@code @Tag("hal")}, which would mean the library's
 * one scheduling policy is only ever exercised on a machine with natives installed. The real code
 * path, including the publish call, runs here.
 */
public final class NtStubs {

  private NtStubs() {}

  /**
   * Replaces the named private static publisher fields with do-nothing proxies.
   *
   * <p>Each field's own declared type is used as the proxy interface, so this works for any
   * NetworkTables publisher without the caller naming the type.
   *
   * @param owner the class declaring the fields
   * @param fieldNames the private static publisher field names
   * @throws IllegalStateException if a field does not exist or is not an interface type — both mean
   *     the class was refactored and this stub is now lying about what it covers
   */
  public static void install(Class<?> owner, String... fieldNames) {
    for (String fieldName : fieldNames) {
      Field field = fieldOf(owner, fieldName);
      Class<?> type = field.getType();
      if (!type.isInterface()) {
        throw new IllegalStateException(
            "NtStubs.install: "
                + owner.getName()
                + "."
                + fieldName
                + " has type "
                + type.getName()
                + ", which is not an interface, so it cannot be proxied. NetworkTables publishers "
                + "are interfaces; if this field changed type, this stub no longer covers the "
                + "publish path and the test would silently kill the JVM instead.");
      }
      set(field, noOpProxy(type));
    }
  }

  /**
   * Sets the named private static publisher fields back to {@code null}, undoing {@link
   * #install(Class, String...)}.
   *
   * @param owner the class declaring the fields
   * @param fieldNames the private static publisher field names
   */
  public static void clear(Class<?> owner, String... fieldNames) {
    for (String fieldName : fieldNames) {
      set(fieldOf(owner, fieldName), null);
    }
  }

  private static Object noOpProxy(Class<?> iface) {
    return Proxy.newProxyInstance(
        NtStubs.class.getClassLoader(),
        new Class<?>[] {iface},
        (proxy, method, args) -> defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> returnType) {
    if (!returnType.isPrimitive() || returnType == void.class) {
      return null;
    }
    if (returnType == boolean.class) {
      return false;
    }
    if (returnType == long.class) {
      return 0L;
    }
    if (returnType == double.class) {
      return 0.0d;
    }
    if (returnType == float.class) {
      return 0.0f;
    }
    if (returnType == char.class) {
      return (char) 0;
    }
    return 0;
  }

  private static Field fieldOf(Class<?> owner, String fieldName) {
    try {
      Field field = owner.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field;
    } catch (NoSuchFieldException e) {
      throw new IllegalStateException(
          "NtStubs: "
              + owner.getName()
              + " has no field \""
              + fieldName
              + "\". It was renamed or removed. Fix: update the field name here — without it the "
              + "test reaches NetworkTableInstance.getDefault() and the JVM exits with no "
              + "diagnostic.",
          e);
    }
  }

  private static void set(Field field, Object value) {
    try {
      field.set(null, value);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("NtStubs: could not write " + field, e);
    }
  }
}

package org.pumpkinlib.arch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.pumpkinlib.core.compat.Clock;

/**
 * ArchUnit rule 12: <strong>Java 17, no preview features, on the 2026 line.</strong>
 *
 * <p>This rule is not expressible in ArchUnit — it is a property of the class file header, not of
 * the type graph — so it is a plain JUnit test that reads the compiled classes directly.
 *
 * <p><strong>Why it matters enough to hand-roll.</strong> Section 10 promises the 2027 {@code
 * org.wpilib} port is an import rewrite. A preview feature makes that false twice over: preview
 * bytecode is refused outright by any JDK other than the exact one that emitted it, and a preview
 * language feature can change shape between releases. Pattern-matching {@code switch} is preview in
 * 17, which is why sealed-interface dispatch in this library uses {@code instanceof} patterns
 * (final since 16).
 *
 * <p>The two checks are independent:
 *
 * <ul>
 *   <li><strong>Major version.</strong> Java 17 is class-file major 61. Anything higher means the
 *       {@code --release 17} flag stopped being applied, which compiles fine on the developer's JDK
 *       21 and fails on a roboRIO.
 *   <li><strong>Minor version.</strong> {@code 0xFFFF} is the preview-features marker. A class file
 *       carrying it will not load on any JVM that is not the emitting version running with
 *       {@code --enable-preview}.
 * </ul>
 */
class BytecodeVersionTest {

  /** Java 17. Raise this only together with the {@code javaRelease} property in the root build. */
  private static final int kMaxClassFileMajor = 61;

  /** The class-file minor version that marks preview features. */
  private static final int kPreviewMinor = 0xFFFF;

  @Test
  @DisplayName("rule 12: every compiled class targets Java 17 with no preview features")
  void everyClassIsJava17WithoutPreview() throws IOException, URISyntaxException {
    Path root = compiledMainClassesRoot();
    List<String> tooNew = new ArrayList<>();
    List<String> preview = new ArrayList<>();
    int scanned = 0;

    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".class")).toList()) {
        scanned++;
        try (InputStream in = Files.newInputStream(file);
            DataInputStream data = new DataInputStream(in)) {
          int magic = data.readInt();
          if (magic != 0xCAFEBABE) {
            continue;
          }
          int minor = data.readUnsignedShort();
          int major = data.readUnsignedShort();
          String name = root.relativize(file).toString();
          if (major > kMaxClassFileMajor) {
            tooNew.add(name + " (major " + major + ")");
          }
          if (minor == kPreviewMinor) {
            preview.add(name);
          }
        }
      }
    }

    assertTrue(
        scanned > 0,
        "found no compiled classes under "
            + root
            + " - this test cannot verify rule 12 and must not pass silently.");
    assertTrue(
        tooNew.isEmpty(),
        () ->
            "rule 12: "
                + tooNew.size()
                + " class(es) target a class-file version above Java 17 (major "
                + kMaxClassFileMajor
                + "). This compiles on the developer's JDK and fails to load on a roboRIO. "
                + "Check that `options.release = 17` is still applied in the root build.gradle:\n  "
                + String.join("\n  ", tooNew));
    assertFalse(
        !preview.isEmpty(),
        () ->
            "rule 12: "
                + preview.size()
                + " class(es) carry the preview-feature marker (minor 0xFFFF). Preview bytecode "
                + "loads only on the exact JDK that emitted it, with --enable-preview, which makes "
                + "\"the 2027 port is an import rewrite\" false. Pattern-matching switch is preview "
                + "in 17 - use instanceof patterns for sealed dispatch:\n  "
                + String.join("\n  ", preview));
  }

  /**
   * Locates {@code build/classes/java/main} from a known main class, so the test does not depend on
   * the working directory Gradle happens to use.
   *
   * @return the root of the compiled main class tree
   */
  private static Path compiledMainClassesRoot() throws URISyntaxException {
    Path clockClass =
        Path.of(Clock.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    if (!Files.isDirectory(clockClass)) {
      throw new IllegalStateException(
          "expected the main classes to be a directory on the test classpath, found "
              + clockClass
              + ". Rule 12 reads class files directly and cannot read them out of a jar here.");
    }
    return clockClass;
  }
}

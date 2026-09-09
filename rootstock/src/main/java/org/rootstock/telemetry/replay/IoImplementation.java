package org.rootstock.telemetry.replay;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares that a type (or one method of it) is an <b>IO implementation</b>: the one layer that is
 * allowed to touch hardware, the filesystem and threads, because everything it reads leaves it through
 * a {@code LoggableInputs} struct.
 *
 * <p>The replay-safety rules are all of the shape <i>"X is a build error <b>outside</b> an IO
 * implementation"</i> — a vendor getter, a thread, a {@code Notifier}, a filesystem read. Inside one,
 * each of those is exactly what the class is for. The rules recognise an IO implementation two ways: a
 * class whose name contains {@code IO}, or a class carrying this annotation. The annotation exists
 * because naming is a convention and a convention is not something a build can rely on — and because
 * the sanctioned home for boot-time file reads is a class that is not called {@code *IO*}.
 *
 * <p>This is <b>not</b> a licence to skip {@code processInputs}. It is the opposite: an IO
 * implementation may read hardware precisely because the values it reads reach the rest of the robot
 * only through {@code RootstockLog.processInputs}, which in {@code REPLAY} mode reads them back from the
 * log instead. A class that annotates itself and then hands a live vendor object to a subsystem has
 * defeated the annotation, and the runtime tripwire is what notices.
 *
 * <p>{@link RetentionPolicy#CLASS} retention: the annotation processor reads it in source and bytecode
 * tooling reads it in the jar, at no runtime cost.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface IoImplementation {

  /**
   * Optional note about what hardware or resource this layer owns.
   *
   * @return the note, or an empty string
   */
  String value() default "";
}

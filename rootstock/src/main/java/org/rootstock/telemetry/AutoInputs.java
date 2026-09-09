package org.rootstock.telemetry;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a hand-written {@code LoggableInputs} struct as a Rootstock IO inputs class.
 *
 * <p><b>This annotation generates nothing, and that is the whole point.</b> Read this before
 * reaching for it expecting a code generator.
 *
 * <h2>What it used to be, and why that is gone</h2>
 *
 * <p>An earlier design shipped {@code @AutoInputs} as a real javac annotation processor: it generated
 * a mirror type so an inputs class could work identically <i>with and without</i> AdvantageKit, and it
 * came with a {@code RootstockInputs} interface and a {@code LogSink}/{@code LogSource} pair. Decision 3
 * made AdvantageKit a required dependency, so "works without AdvantageKit" stopped being a property
 * anyone needs, and the processor, the mirror interface and the sink/source pair were all deleted.
 * Every {@code *Inputs} class in Rootstock now implements
 * {@code org.littletonrobotics.junction.inputs.LoggableInputs} directly with hand-written
 * {@code toLog}/{@code fromLog} — which is also why {@code @AutoLog} is not used: the log schema is a
 * published contract asserted by literal string, and a field rename must not silently rename a
 * contract key.
 *
 * <h2>What it is now</h2>
 *
 * <p>A source marker, with {@link RetentionPolicy#CLASS} retention so bytecode tooling can see it. It
 * says one thing: <i>this type is an IO inputs struct whose fields reach the log through
 * {@link RootstockLog#processInputs}</i>. Two consumers care:
 *
 * <ul>
 *   <li>{@code rootstock-lint} uses it to locate inputs structs when enforcing the input invariant —
 *       no value that reaches the log through {@code processInputs} may ever be marked
 *       {@link Demotable#YES}. See {@link Demotable} for why that invariant is load-bearing.
 *   <li>A reader. An {@code *Inputs} class with a hand-written {@code toLog} looks like ordinary code
 *       until you know that every field in it is a replayed input and that adding one changes the
 *       schema.
 * </ul>
 *
 * <p>It is optional. Nothing in the library requires it, {@code processInputs} does not look for it,
 * and an unannotated {@code LoggableInputs} behaves identically. It is documentation the build can
 * read.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface AutoInputs {

  /**
   * Optional note about what this struct reads, for the reader and for the lint's diagnostics.
   *
   * @return the note, or an empty string
   */
  String value() default "";
}

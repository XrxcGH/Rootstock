package org.rootstock.telemetry.schema;

import java.util.List;
import java.util.Objects;

/**
 * One named block of the schema — the section-3 tables of {@code design/04}, one record each.
 *
 * <p>Sections exist so a diff between two releases says <i>which</i> block changed. "A key moved"
 * and "the drivetrain block moved" are different sizes of problem, and the second one means every
 * swerve team's AdvantageScope layout is stale.
 *
 * @param title the section heading, e.g. {@code "3.2 Drivetrain - Rootstock/Drive/"}
 * @param entries the rows, in the order the design table lists them
 */
public record SchemaSection(String title, List<SchemaEntry> entries) {

  /** Copies the row list defensively so a published section cannot be edited through its source. */
  public SchemaSection {
    Objects.requireNonNull(title, "SchemaSection.title must not be null.");
    Objects.requireNonNull(entries, "SchemaSection.entries must not be null.");
    entries = List.copyOf(entries);
  }
}

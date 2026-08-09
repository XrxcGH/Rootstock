package org.pumpkinlib.core.spi;

// D26's downward-crossing-value package (ArchUnit rule 9). Telemetry (design/04 section 2.2) is
// still the SOLE definition site for what these constants MEAN; only the package moved, by D33.
// A change to what a Tier means is a change to design/04, not to this file.

/**
 * How important a logged key is, and therefore whether it survives the FMS gate.
 *
 * <p>Orthogonal to {@code org.pumpkinlib.telemetry.Demotable}, which answers a different question:
 * Tier answers <i>"may this key disappear when the FMS gate raises {@code minimumTier}?"</i>;
 * {@code Demotable} answers <i>"may this key be sampled slower under sustained load?"</i>
 */
public enum Tier {

  /**
   * Always present in the log, including on FMS. Anything you would need to explain a lost match.
   *
   * <p>CRITICAL keys are never removed from the schema by any mechanism. That is a stronger promise
   * than STANDARD's, and it is why a device-reset counter is CRITICAL: a reset counter that can be
   * argued away is a reset counter that will be, and the failure it catches — a setpoint sent once,
   * forever, then silently dropped by a mid-match device reset — is invisible unless something counts
   * it.
   */
  CRITICAL,

  /**
   * The default. Present whenever {@code minimumTier} is STANDARD or DEBUG — which INCLUDES the
   * FMS-attached case, because the FMS gate raises {@code minimumTier} to STANDARD and no higher.
   *
   * <p>A STANDARD key therefore SURVIVES an FMS attach; it disappears only if a team deliberately
   * sets {@code minimumTier = CRITICAL}, which nothing in the library does. The gate drops DEBUG and
   * only DEBUG.
   *
   * <p>Also subject to an explicit {@code Demotable} marking, which changes RATE and never presence.
   */
  STANDARD,

  /**
   * Dropped when FMS-attached.
   *
   * <p>Reference-typed values at this tier are supplier-only, so the value is never computed when the
   * tier is off — the cost of a DEBUG key on FMS is one enum comparison, not one allocation.
   */
  DEBUG
}

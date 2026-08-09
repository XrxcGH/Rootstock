package org.pumpkinlib.core.alert;

import edu.wpi.first.wpilibj.Alert.AlertType;

/**
 * Ordered severity for a {@link PumpkinAlert}.
 *
 * <p>Maps 1:1 to WPILib's {@code Alert.AlertType} so the stock dashboard Alerts widget keeps
 * working, but exists as its own type because PumpkinLib needs to <em>compare</em> and <em>roll
 * up</em> severities ({@link AlertRegistry#worst()}), and an enum you can order is what makes that
 * one line instead of a switch at every call site.
 *
 * <p>Declaration order is deliberate and load-bearing: {@code INFO < WARNING < ERROR}, so
 * {@link #compareTo} and {@link java.util.Comparator} sort the way a human expects and
 * {@link #atLeast(Severity)} reads correctly.
 *
 * <p><strong>Severity is not the same question as "can we play?"</strong> That second, orthogonal
 * question is {@link MatchImpact}, and every {@code error}/{@code warning} call site must answer it.
 * "The elevator encoder is unplugged" and "you have not tuned kG yet" are both honestly
 * {@code ERROR}; only one of them means do not take the field.
 */
public enum Severity {
  /** Informational. Never blocks a match — see {@link MatchImpact}. */
  INFO,
  /** Something is wrong and worth fixing. */
  WARNING,
  /** Something is broken. */
  ERROR;

  /**
   * The WPILib alert type this severity displays as.
   *
   * @return the matching {@code Alert.AlertType}
   */
  public AlertType toWpi() {
    return switch (this) {
      case INFO -> AlertType.kInfo;
      case WARNING -> AlertType.kWarning;
      case ERROR -> AlertType.kError;
    };
  }

  /**
   * The severity corresponding to a WPILib alert type.
   *
   * @param type the WPILib alert type; must not be null
   * @return the matching severity
   * @throws IllegalArgumentException if {@code type} is null
   */
  public static Severity from(AlertType type) {
    if (type == null) {
      throw new IllegalArgumentException(
          "Severity.from(AlertType) was given null. Pass Alert.AlertType.kInfo, kWarning or "
              + "kError, or use Severity.INFO/WARNING/ERROR directly.");
    }
    return switch (type) {
      case kInfo -> INFO;
      case kWarning -> WARNING;
      case kError -> ERROR;
    };
  }

  /**
   * Whether this severity is at least as severe as {@code other}.
   *
   * @param other the severity to compare against; must not be null
   * @return true when {@code this >= other} in the INFO &lt; WARNING &lt; ERROR ordering
   * @throws IllegalArgumentException if {@code other} is null
   */
  public boolean atLeast(Severity other) {
    if (other == null) {
      throw new IllegalArgumentException(
          "Severity.atLeast(other) was given null. Pass Severity.INFO, WARNING or ERROR.");
    }
    return compareTo(other) >= 0;
  }

  /**
   * The more severe of two severities.
   *
   * @param a first severity; must not be null
   * @param b second severity; must not be null
   * @return whichever of {@code a} and {@code b} is more severe
   * @throws IllegalArgumentException if either argument is null
   */
  public static Severity max(Severity a, Severity b) {
    if (a == null || b == null) {
      throw new IllegalArgumentException(
          "Severity.max(a, b) was given null (a=" + a + ", b=" + b + "). Both arguments are "
              + "required; use Severity.INFO as the neutral element if you need one.");
    }
    return a.compareTo(b) >= 0 ? a : b;
  }
}

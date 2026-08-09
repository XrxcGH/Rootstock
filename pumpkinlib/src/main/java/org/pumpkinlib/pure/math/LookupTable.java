package org.pumpkinlib.pure.math;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An immutable, strictly-increasing-in-x, linearly interpolating lookup table.
 *
 * <p>This is the shape every shooter distance table, every kG-versus-extension table and every
 * hand-measured feedforward curve in an FRC codebase actually wants, and it is the shape those
 * tables are usually written as: a pair of parallel arrays and a for-loop with an off-by-one in it.
 *
 * <p><b>Reads outside the table's domain are clamped, never extrapolated.</b> Every table here is
 * measured data; past the last measured point there is no data, and a linear extrapolation of one is
 * a confident number nobody checked. A shooter that spins to a clamped 4200 RPM at 8 m is wrong in a
 * way a student can see; one that extrapolates to 9100 RPM is wrong in a way that breaks a wheel.
 *
 * <p><b>Tier 0.</b> Zero {@code edu.wpi.first} imports (ArchUnit rule 8).
 */
public final class LookupTable {

  private final double[] m_xs;
  private final double[] m_ys;
  private final String m_name;

  private LookupTable(String name, double[] xs, double[] ys) {
    m_name = name;
    m_xs = xs;
    m_ys = ys;
  }

  /**
   * A table from parallel arrays.
   *
   * @param name a short name used in error messages and {@link #describe()}, e.g. "ShooterRpm"
   * @param xs the keys; must be non-empty, finite and strictly increasing
   * @param ys the values, one per key; must be the same length as {@code xs} and finite
   * @return the table
   * @throws IllegalArgumentException if the arrays are empty, differ in length, contain a
   *     non-finite value, or {@code xs} is not strictly increasing
   */
  public static LookupTable of(String name, double[] xs, double[] ys) {
    if (xs.length == 0) {
      throw new IllegalArgumentException(
          "LookupTable \"" + name + "\": the table is empty. "
              + "Fix: add at least one (x, y) point — a one-point table is a legal constant.");
    }
    if (xs.length != ys.length) {
      throw new IllegalArgumentException(
          "LookupTable \""
              + name
              + "\": got "
              + xs.length
              + " keys and "
              + ys.length
              + " values; they must be the same length. "
              + "Fix: every x needs exactly one y.");
    }
    for (int i = 0; i < xs.length; i++) {
      if (!Double.isFinite(xs[i]) || !Double.isFinite(ys[i])) {
        throw new IllegalArgumentException(
            "LookupTable \""
                + name
                + "\": point "
                + i
                + " is ("
                + xs[i]
                + ", "
                + ys[i]
                + "); both coordinates must be finite. "
                + "Fix: a NaN here usually means a measurement that was never taken.");
      }
      if (i > 0 && !(xs[i] > xs[i - 1])) {
        throw new IllegalArgumentException(
            "LookupTable \""
                + name
                + "\": keys must be STRICTLY increasing, but x["
                + (i - 1)
                + "] = "
                + xs[i - 1]
                + " and x["
                + i
                + "] = "
                + xs[i]
                + ". Fix: sort your points by x and remove the duplicate key — two y values for "
                + "one x has no defined answer.");
      }
    }
    return new LookupTable(name, Arrays.copyOf(xs, xs.length), Arrays.copyOf(ys, ys.length));
  }

  /**
   * A builder that sorts and validates for you, so points may be added in any order.
   *
   * @param name a short name used in error messages and {@link #describe()}
   * @return a new builder
   */
  public static Builder builder(String name) {
    return new Builder(name);
  }

  /**
   * The interpolated value at a key, clamped to the table's domain.
   *
   * @param x the key
   * @return the interpolated value; the first y below the domain, the last y above it
   */
  public double get(double x) {
    if (m_xs.length == 1 || x <= m_xs[0]) {
      return m_ys[0];
    }
    int last = m_xs.length - 1;
    if (x >= m_xs[last]) {
      return m_ys[last];
    }
    // binarySearch returns (-(insertionPoint) - 1) for a miss; the exact-hit case is handled too.
    int found = Arrays.binarySearch(m_xs, x);
    if (found >= 0) {
      return m_ys[found];
    }
    int upper = -found - 1;
    int lower = upper - 1;
    double t = (x - m_xs[lower]) / (m_xs[upper] - m_xs[lower]);
    return m_ys[lower] + (m_ys[upper] - m_ys[lower]) * t;
  }

  /**
   * Whether a key falls inside the measured domain, so a caller can warn rather than silently take
   * a clamped value.
   *
   * @param x the key
   * @return true if {@code x} is within {@link #domain()}
   */
  public boolean isInDomain(double x) {
    return domain().contains(x);
  }

  /**
   * The measured key range.
   *
   * @return the closed interval from the first key to the last
   */
  public Interval domain() {
    return new Interval(m_xs[0], m_xs[m_xs.length - 1]);
  }

  /**
   * The number of points in the table.
   *
   * @return the point count, always at least one
   */
  public int size() {
    return m_xs.length;
  }

  /**
   * The name this table was built with.
   *
   * @return the name
   */
  public String name() {
    return m_name;
  }

  /**
   * The key at an index.
   *
   * @param index the zero-based index, {@code 0 <= index < size()}
   * @return the key
   */
  public double keyAt(int index) {
    return m_xs[index];
  }

  /**
   * The value at an index.
   *
   * @param index the zero-based index, {@code 0 <= index < size()}
   * @return the value
   */
  public double valueAt(int index) {
    return m_ys[index];
  }

  /**
   * A multi-line human-readable dump, for {@code describe()} output.
   *
   * @return the table's name, point count, domain and every point
   */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    sb.append(m_name)
        .append(": ")
        .append(m_xs.length)
        .append(" points over ")
        .append(domain().describe())
        .append(" (clamped outside)");
    for (int i = 0; i < m_xs.length; i++) {
      sb.append(String.format("%n  %10.4f -> %10.4f", m_xs[i], m_ys[i]));
    }
    return sb.toString();
  }

  @Override
  public String toString() {
    return "LookupTable[" + m_name + ", " + m_xs.length + " points, " + domain().describe() + "]";
  }

  /** Accumulates points in any order and sorts them at {@link #build()}. */
  public static final class Builder {
    private final String m_builderName;
    private final List<double[]> m_points = new ArrayList<>();

    private Builder(String name) {
      m_builderName = name;
    }

    /**
     * Adds one point.
     *
     * @param x the key
     * @param y the value
     * @return this builder, for chaining
     */
    public Builder add(double x, double y) {
      m_points.add(new double[] {x, y});
      return this;
    }

    /**
     * Builds the immutable table, sorting the accumulated points by key.
     *
     * @return the table
     * @throws IllegalArgumentException if no points were added, a coordinate is non-finite, or two
     *     points share a key
     */
    public LookupTable build() {
      m_points.sort((a, b) -> Double.compare(a[0], b[0]));
      double[] xs = new double[m_points.size()];
      double[] ys = new double[m_points.size()];
      for (int i = 0; i < m_points.size(); i++) {
        xs[i] = m_points.get(i)[0];
        ys[i] = m_points.get(i)[1];
      }
      return LookupTable.of(m_builderName, xs, ys);
    }
  }
}

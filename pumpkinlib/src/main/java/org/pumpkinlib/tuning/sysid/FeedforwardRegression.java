package org.pumpkinlib.tuning.sysid;

import edu.wpi.first.math.MatBuilder;
import edu.wpi.first.math.Nat;
import edu.wpi.first.math.VecBuilder;
import java.util.Locale;
import java.util.Objects;
import org.pumpkinlib.control.MechanismArchetype;

/**
 * Streaming ordinary least squares for kS, kV, kA and kG — constant memory, whatever the sample
 * count.
 *
 * <h2>Why the normal equations, and why this is the reason on-robot identification works at all</h2>
 *
 * <p>The model is linear in the gains. For a gravity-free mechanism {@code u = kS·sgn(v) + kV·v +
 * kA·a}; an elevator adds a constant {@code kG}; an arm adds {@code kG·cos(theta)} where theta is
 * the angle <em>from horizontal</em>. Stacking N samples gives an overdetermined system {@code X β =
 * y}, whose least-squares solution satisfies {@code (Xᵀ X) β = Xᵀ y}.
 *
 * <p>The decisive fact is that {@code Xᵀ X} is 3×3 or 4×4 <b>regardless of N</b>. So no sample is
 * ever stored: each loop adds {@code φ φᵀ} into a 4×4 array and {@code φ u} into a 4-vector, which
 * is at most twenty multiply-adds. A roboRIO can do that inline in a 20 ms loop with no allocation,
 * which is why the gains can appear on the dashboard twenty seconds after the sweep ends instead of
 * after a laptop, a log pull and a nine-step GUI ritual.
 *
 * <p>The quality metrics come out of the same accumulators, exactly: {@code SSE = yᵀy − 2βᵀ(Xᵀy) +
 * βᵀ(XᵀX)β} and {@code SST = yᵀy − (Σy)²/N}. That identity is not an approximation, and it means R²
 * and RMSE also cost nothing in memory.
 *
 * <h2>The arm regressor takes the angle from horizontal, not the raw position</h2>
 *
 * <p>Passing a raw position into {@code cos} is correct only when the mechanism's zero happens to be
 * horizontal. For any arm whose zero is somewhere else, the cosine is wrong at <em>every</em> angle,
 * not merely at one, and the resulting kG is a number that looks plausible and holds the arm nowhere.
 * The caller supplies {@code measuredSi() - horizontalReferenceSi()}.
 *
 * <p>Deliberately not thread-safe: it is fed from the robot loop and solved between steps.
 */
public final class FeedforwardRegression {

  /** Fewest samples that can produce a fit: 4 s at 50 Hz. Below this the answer is noise. */
  public static final int kMinimumSamples = 200;

  /** A regressor column whose variance is below this carried no information about its gain. */
  public static final double kMinimumColumnVariance = 1e-9;

  private final MechanismArchetype m_archetype;
  private final int m_n;
  private final double[][] m_xtx;
  private final double[] m_xty;
  private final double[] m_phi;
  private final double[] m_columnSum;
  private final double[] m_columnSumSquares;
  private double m_yty;
  private double m_sumY;
  private int m_samples;

  /**
   * Start a fit for one mechanism model.
   *
   * @param archetype which model to fit; gravity archetypes get a fourth regressor column
   * @throws NullPointerException if the archetype is null
   */
  public FeedforwardRegression(MechanismArchetype archetype) {
    m_archetype = Objects.requireNonNull(archetype, "archetype");
    m_n = archetype.hasGravity() ? 4 : 3;
    m_xtx = new double[m_n][m_n];
    m_xty = new double[m_n];
    m_phi = new double[m_n];
    m_columnSum = new double[m_n];
    m_columnSumSquares = new double[m_n];
  }

  /**
   * Add one loop of data. At most twenty multiply-adds; allocates nothing.
   *
   * <p>Non-finite voltage or velocity samples are dropped entirely rather than poisoning the
   * accumulators — one NaN in {@code XᵀX} makes every gain NaN, and the student would see four NaNs
   * with no clue which loop caused them. A non-finite acceleration is treated as zero for the kA
   * column only, which is the normal case on hardware that reports no acceleration signal and has
   * not yet produced a differenced estimate.
   *
   * @param angleFromHorizontal radians from horizontal, for {@link MechanismArchetype#ARM} only;
   *     ignored otherwise
   * @param velocity m/s or rad/s
   * @param acceleration m/s² or rad/s²; NaN is treated as zero for the kA column
   * @param volts the <b>applied</b> volts, preferably as measured at the device rather than as
   *     commanded — on a sagging battery the two differ by half a volt, and fitting against the
   *     commanded value biases kV high
   */
  public void add(
      double angleFromHorizontal, double velocity, double acceleration, double volts) {
    if (!Double.isFinite(volts) || !Double.isFinite(velocity)) {
      return;
    }
    double a = Double.isFinite(acceleration) ? acceleration : 0.0;

    int k = 0;
    if (m_archetype == MechanismArchetype.ELEVATOR) {
      m_phi[k++] = 1.0;
    } else if (m_archetype == MechanismArchetype.ARM) {
      m_phi[k++] = Math.cos(angleFromHorizontal);
    }
    m_phi[k++] = Math.signum(velocity);
    m_phi[k++] = velocity;
    m_phi[k] = a;

    for (int i = 0; i < m_n; i++) {
      double pi = m_phi[i];
      m_xty[i] += pi * volts;
      m_columnSum[i] += pi;
      m_columnSumSquares[i] += pi * pi;
      for (int j = i; j < m_n; j++) {
        // Only the upper triangle is accumulated; solve() mirrors it. XᵀX is symmetric by
        // construction, so accumulating both halves would be twice the work for the same numbers.
        m_xtx[i][j] += pi * m_phi[j];
      }
    }
    m_yty += volts * volts;
    m_sumY += volts;
    m_samples++;
  }

  /**
   * How many samples have been accepted.
   *
   * @return the count, which is not the number of loops if some carried NaN
   */
  public int samples() {
    return m_samples;
  }

  /**
   * Which model this is fitting.
   *
   * @return the archetype
   */
  public MechanismArchetype archetype() {
    return m_archetype;
  }

  /**
   * How many parameters the model has.
   *
   * @return 3 for a gravity-free archetype, 4 for an elevator or an arm
   */
  public int parameterCount() {
    return m_n;
  }

  /**
   * Solve for the gains and score the fit.
   *
   * <p>Two things are checked before the solve, because both produce a confidently wrong answer
   * rather than an obvious one: too little data, and a regressor column that never varied. The
   * second is the classic sweep mistake — running only the quasistatic ramp means the acceleration
   * column is a constant near zero, kA is unidentifiable, and a naive solve returns whatever the
   * numerics happen to produce.
   *
   * @return the fit, with no physics sanity warnings yet; call {@link
   *     FeedforwardFit#sanityBounded} next
   * @throws IdentificationException if there are fewer than {@value #kMinimumSamples} samples, or a
   *     column carried no information
   */
  public FeedforwardFit solve() {
    if (m_samples < kMinimumSamples) {
      throw new IdentificationException(
          FitFailure.INSUFFICIENT_DATA,
          "Only "
              + m_samples
              + " samples were collected and at least "
              + kMinimumSamples
              + " are needed, which is about 4 seconds of motion. Did the mechanism actually move? "
              + "Check that the sweep was not aborted in its first second.");
    }

    double[][] a = new double[m_n][m_n];
    for (int i = 0; i < m_n; i++) {
      for (int j = 0; j < m_n; j++) {
        a[i][j] = (j >= i) ? m_xtx[i][j] : m_xtx[j][i];
      }
    }

    for (int i = 0; i < m_n; i++) {
      if (i == gravityColumnIndex()) {
        // The elevator's gravity column is the constant 1 by construction, so its variance is zero
        // and always will be. That is not rank deficiency, it is what a constant term looks like.
        continue;
      }
      double mean = m_columnSum[i] / m_samples;
      double variance = m_columnSumSquares[i] / m_samples - mean * mean;
      if (variance < kMinimumColumnVariance) {
        String column = columnName(i);
        throw new IdentificationException(
            FitFailure.RANK_DEFICIENT,
            "The "
                + column
                + " column never varied, so the gain it multiplies cannot be measured from this "
                + "data. "
                + ("a".equals(column)
                    ? "Run the DYNAMIC step, not just the ramp - kA is only visible while the "
                        + "mechanism is speeding up."
                    : "The mechanism did not move enough. Re-run with a larger step or a longer "
                        + "sweep."));
      }
    }

    double[] beta = (m_n == 3) ? solve3(a, m_xty) : solve4(a, m_xty);

    double bXtY = 0.0;
    for (int i = 0; i < m_n; i++) {
      bXtY += beta[i] * m_xty[i];
    }
    double bXtXb = 0.0;
    for (int i = 0; i < m_n; i++) {
      for (int j = 0; j < m_n; j++) {
        bXtXb += beta[i] * a[i][j] * beta[j];
      }
    }
    double sse = m_yty - 2.0 * bXtY + bXtXb;
    double sst = m_yty - m_sumY * m_sumY / m_samples;
    double r2 = (sst > 1e-12) ? 1.0 - sse / sst : Double.NaN;
    double rmse = Math.sqrt(Math.max(sse, 0.0) / m_samples);

    return FeedforwardFit.from(m_archetype, beta, r2, rmse, m_samples);
  }

  /** Forget everything and start a new fit. Reuses the arrays, so this allocates nothing. */
  public void reset() {
    for (int i = 0; i < m_n; i++) {
      java.util.Arrays.fill(m_xtx[i], 0.0);
      m_xty[i] = 0.0;
      m_phi[i] = 0.0;
      m_columnSum[i] = 0.0;
      m_columnSumSquares[i] = 0.0;
    }
    m_yty = 0.0;
    m_sumY = 0.0;
    m_samples = 0;
  }

  /**
   * Progress on one line, for the live UI during a sweep.
   *
   * @return e.g. {@code "ELEVATOR fit: 412/200 samples, 4 parameters"}
   */
  public String describe() {
    return String.format(
        Locale.ROOT,
        "%s fit: %d/%d samples, %d parameters",
        m_archetype,
        m_samples,
        kMinimumSamples,
        m_n);
  }

  /** The index of the constant gravity column, or -1 when this model has no gravity term. */
  private int gravityColumnIndex() {
    return m_archetype == MechanismArchetype.ELEVATOR ? 0 : -1;
  }

  private String columnName(int index) {
    if (m_archetype == MechanismArchetype.ELEVATOR) {
      return switch (index) {
        case 0 -> "gravity";
        case 1 -> "sgn(v)";
        case 2 -> "v";
        default -> "a";
      };
    }
    if (m_archetype == MechanismArchetype.ARM) {
      return switch (index) {
        case 0 -> "cos(theta)";
        case 1 -> "sgn(v)";
        case 2 -> "v";
        default -> "a";
      };
    }
    return switch (index) {
      case 0 -> "sgn(v)";
      case 1 -> "v";
      default -> "a";
    };
  }

  /**
   * The 3×3 solve, in wpimath.
   *
   * <p>Accumulation is done in plain {@code double[][]} rather than in {@code Matrix} objects
   * because {@code Matrix} has no in-place add — every accumulation step would allocate a new matrix
   * on the robot loop, which is exactly what this class exists to avoid. The matrices are built once
   * here, at solve time, off the hot path.
   */
  private static double[] solve3(double[][] a, double[] b) {
    var matrix =
        MatBuilder.fill(
            Nat.N3(),
            Nat.N3(),
            a[0][0], a[0][1], a[0][2],
            a[1][0], a[1][1], a[1][2],
            a[2][0], a[2][1], a[2][2]);
    var rhs = VecBuilder.fill(b[0], b[1], b[2]);
    var x = matrix.solveFullPivHouseholderQr(rhs);
    return new double[] {x.get(0, 0), x.get(1, 0), x.get(2, 0)};
  }

  /** The 4×4 solve, for the two gravity archetypes. See {@link #solve3} for why it is built here. */
  private static double[] solve4(double[][] a, double[] b) {
    var matrix =
        MatBuilder.fill(
            Nat.N4(),
            Nat.N4(),
            a[0][0], a[0][1], a[0][2], a[0][3],
            a[1][0], a[1][1], a[1][2], a[1][3],
            a[2][0], a[2][1], a[2][2], a[2][3],
            a[3][0], a[3][1], a[3][2], a[3][3]);
    var rhs = VecBuilder.fill(b[0], b[1], b[2], b[3]);
    var x = matrix.solveFullPivHouseholderQr(rhs);
    return new double[] {x.get(0, 0), x.get(1, 0), x.get(2, 0), x.get(3, 0)};
  }
}

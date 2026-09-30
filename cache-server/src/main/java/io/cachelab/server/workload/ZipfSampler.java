package io.cachelab.server.workload;

import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Samples ranks {@code 0..n-1} from a Zipf distribution with exponent {@code s}: rank {@code r} has
 * probability proportional to {@code 1 / (r + 1)^s}, so rank 0 is the hottest.
 *
 * <p>The cumulative distribution is precomputed once per {@code (n, s)} and shared between samplers
 * (it is immutable); each sample is a binary search, O(log n), allocation-free.
 *
 * <p>Thread-safe: instances are immutable; randomness comes from the caller's {@link
 * SplittableRandom}.
 */
public final class ZipfSampler {

  private static final Map<CdfKey, double[]> CDFS = new ConcurrentHashMap<>();

  private final int n;
  private final double[] cdf;

  /**
   * Creates a sampler.
   *
   * @param n number of ranks, at least 1
   * @param s exponent, finite and at least 0 (0 is uniform)
   * @throws IllegalArgumentException if {@code n < 1} or {@code s} is negative or not finite
   */
  public ZipfSampler(int n, double s) {
    if (n < 1) {
      throw new IllegalArgumentException("n must be >= 1: " + n);
    }
    if (!(s >= 0) || Double.isInfinite(s)) {
      throw new IllegalArgumentException("s must be finite and >= 0: " + s);
    }
    this.n = n;
    this.cdf = CDFS.computeIfAbsent(new CdfKey(n, s), ZipfSampler::buildCdf);
  }

  private static double[] buildCdf(CdfKey key) {
    double[] cdf = new double[key.n()];
    double sum = 0;
    for (int r = 0; r < key.n(); r++) {
      sum += 1.0 / Math.pow(r + 1, key.s());
      cdf[r] = sum;
    }
    for (int r = 0; r < key.n(); r++) {
      cdf[r] /= sum;
    }
    cdf[key.n() - 1] = 1.0;
    return cdf;
  }

  /**
   * Draws one rank.
   *
   * @param rnd the random stream; never {@code null}
   * @return a rank in {@code [0, n)}
   */
  public int sample(SplittableRandom rnd) {
    double u = rnd.nextDouble();
    int i = Arrays.binarySearch(cdf, u);
    int rank = i >= 0 ? i + 1 : -i - 1; // first index whose cdf exceeds u
    return Math.min(rank, n - 1);
  }

  /**
   * Returns the number of ranks.
   *
   * @return {@code n}
   */
  public int size() {
    return n;
  }

  private record CdfKey(int n, double s) {}
}

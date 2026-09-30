package io.cachelab.bench;

import java.util.SplittableRandom;

/** Pre-generates a seeded Zipf-distributed key stream (CDF plus binary search). */
final class ZipfKeys {

  private ZipfKeys() {}

  static Integer[] generate(int length, int keySpace, double s, long seed) {
    double[] cdf = new double[keySpace];
    double sum = 0;
    for (int i = 0; i < keySpace; i++) {
      sum += 1.0 / Math.pow(i + 1, s);
      cdf[i] = sum;
    }
    Integer[] boxed = new Integer[keySpace];
    for (int i = 0; i < keySpace; i++) {
      cdf[i] /= sum;
      boxed[i] = i; // shared boxes: the benchmark measures the cache, not autoboxing
    }
    SplittableRandom rnd = new SplittableRandom(seed);
    Integer[] keys = new Integer[length];
    for (int i = 0; i < length; i++) {
      keys[i] = boxed[search(cdf, rnd.nextDouble())];
    }
    return keys;
  }

  private static int search(double[] cdf, double u) {
    int lo = 0;
    int hi = cdf.length - 1;
    while (lo < hi) {
      int mid = (lo + hi) >>> 1;
      if (cdf[mid] < u) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return lo;
  }
}

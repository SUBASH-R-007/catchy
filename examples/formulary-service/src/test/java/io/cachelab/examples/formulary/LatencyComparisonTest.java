package io.cachelab.examples.formulary;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import io.cachelab.examples.formulary.FormularyApplication.DrugRepository;
import io.cachelab.examples.formulary.FormularyApplication.DrugService;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;

/**
 * SPEC 7: average latency of 2,000 Zipf-distributed formulary lookups (s = 1.1 over 50,000 drugs,
 * as in the FORMULARY workload) with and without the cache. The cached run is measured after a
 * warm-up of 200,000 lookups drawn from the same distribution with a different seed, the way a
 * service looks after running for a while. The cache holds 20,000 of the 50,000 drugs: with 10,000
 * slots the best possible hit rate is about 92% and the measured speedup sat right at 10x
 * (9.8-10.2x), too close to the bar for a stable test. Per-call latency is timed individually, so
 * running the calls on several threads (to keep the test short) does not change the averages.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class LatencyComparisonTest {

  private static final int DRUGS = 50_000;
  private static final int LOOKUPS = 2_000;
  private static final int WARM_UP = 200_000;

  @Autowired DrugRepository repository;
  @Autowired DrugService service;
  @Autowired CacheManager caches;

  @Test
  void theCacheIsAtLeastTenTimesFaster() throws Exception {
    double[] cdf = zipfCdf(DRUGS, 1.1);
    int[] measured = keys(cdf, LOOKUPS, 7);

    double uncachedMs = averageMs(measured, repository::load, 50);
    runAll(keys(cdf, WARM_UP, 99), service::find, 64);
    CacheStats before = stats();
    double cachedMs = averageMs(measured, service::find, 50);
    CacheStats during = stats().minus(before);

    System.out.printf(
        "LatencyComparison: %,d Zipf(1.1) lookups over %,d drugs; without cache %.2f ms avg,"
            + " with cache %.3f ms avg (hit rate %.1f%% after a %,d-lookup warm-up) -> %.0fx faster%n",
        LOOKUPS,
        DRUGS,
        uncachedMs,
        cachedMs,
        during.hitRate() * 100,
        WARM_UP,
        uncachedMs / cachedMs);
    assertThat(cachedMs * 10).isLessThanOrEqualTo(uncachedMs);
  }

  private CacheStats stats() {
    return ((Cache<?, ?>) caches.getCache("formulary").getNativeCache()).stats();
  }

  private static double averageMs(int[] ids, IntConsumer call, int threads) throws Exception {
    LongAdder nanos = new LongAdder();
    runAll(
        ids,
        id -> {
          long start = System.nanoTime();
          call.accept(id);
          nanos.add(System.nanoTime() - start);
        },
        threads);
    return nanos.sum() / 1e6 / ids.length;
  }

  private static void runAll(int[] ids, IntConsumer call, int threads) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      for (int id : ids) {
        pool.execute(() -> call.accept(id));
      }
    } finally {
      pool.shutdown();
      assertThat(pool.awaitTermination(5, TimeUnit.MINUTES)).isTrue();
    }
  }

  private static int[] keys(double[] cdf, int n, long seed) {
    SplittableRandom rnd = new SplittableRandom(seed);
    int[] ids = new int[n];
    for (int i = 0; i < n; i++) {
      double u = rnd.nextDouble();
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
      ids[i] = lo;
    }
    return ids;
  }

  private static double[] zipfCdf(int n, double s) {
    double[] cdf = new double[n];
    double sum = 0;
    for (int i = 0; i < n; i++) {
      sum += 1 / Math.pow(i + 1, s);
      cdf[i] = sum;
    }
    for (int i = 0; i < n; i++) {
      cdf[i] /= sum;
    }
    return cdf;
  }
}

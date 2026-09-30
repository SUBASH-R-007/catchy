package io.cachelab.diagnostics;

import io.cachelab.Cache;
import io.cachelab.CacheBuilder;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cache-stampede test (SPEC 5): many threads ask for the same missing key at the same moment,
 * through {@code getOrLoad}. With single-flight loading the slow loader runs exactly once and every
 * thread receives the same value.
 *
 * <p>Thread-safety: each call uses its own cache and threads. Blocks for about the loader delay.
 */
public final class StampedeTest {

  /** Default number of threads. */
  public static final int DEFAULT_THREADS = 200;

  /** Default loader delay. */
  public static final Duration DEFAULT_LOADER_DELAY = Duration.ofMillis(200);

  private StampedeTest() {}

  /**
   * Runs the test with 200 threads and a 200 ms loader.
   *
   * @return the result
   */
  public static StampedeResult run() {
    return run(DEFAULT_THREADS, DEFAULT_LOADER_DELAY);
  }

  /**
   * Runs the test.
   *
   * @param threads concurrent callers, 1 to 500
   * @param loaderDelay how long the loader takes, 0 to 2 s
   * @return the result, never null
   * @throws IllegalArgumentException if a parameter is out of range
   */
  public static StampedeResult run(int threads, Duration loaderDelay) {
    Objects.requireNonNull(loaderDelay, "loaderDelay");
    if (threads < 1 || threads > 500) {
      throw new IllegalArgumentException("threads must be between 1 and 500, but was " + threads);
    }
    if (loaderDelay.isNegative() || loaderDelay.compareTo(Duration.ofSeconds(2)) > 0) {
      throw new IllegalArgumentException("loaderDelay must be between 0 and 2 s: " + loaderDelay);
    }
    AtomicInteger loaderCalls = new AtomicInteger();
    String[] values = new String[threads];
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch go = new CountDownLatch(1);
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().name("stampede").maximumSize(16).build()) {
      Thread[] workers = new Thread[threads];
      for (int i = 0; i < threads; i++) {
        int slot = i;
        workers[i] =
            new Thread(
                () -> {
                  ready.countDown();
                  awaitQuietly(go);
                  values[slot] =
                      cache.getOrLoad(
                          "stampede:missing-key",
                          k -> {
                            int call = loaderCalls.incrementAndGet();
                            sleepQuietly(loaderDelay);
                            return "value-from-load-" + call;
                          });
                },
                "cachelab-stampede-" + i);
        workers[i].setDaemon(true);
        workers[i].start();
      }
      awaitQuietly(ready);
      long start = System.nanoTime();
      go.countDown();
      for (Thread w : workers) {
        joinQuietly(w, loaderDelay.toMillis() + 10_000);
      }
      long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
      boolean allSame = values[0] != null && Arrays.stream(values).allMatch(values[0]::equals);
      return new StampedeResult(threads, loaderCalls.get(), allSame, durationMs);
    }
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void sleepQuietly(Duration d) {
    try {
      Thread.sleep(d); // simulates a slow database call
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void joinQuietly(Thread t, long millis) {
    try {
      t.join(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

package io.cachelab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoadingAndListenersTest {

  @Test
  void aListenerThatReadsTheCacheDoesNotDeadlock() {
    AtomicReference<Cache<String, String>> self = new AtomicReference<>();
    List<Optional<String>> seenByListener = new ArrayList<>();
    Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder()
            .maximumSize(1)
            .removalListener(
                (k, v, cause) -> {
                  // Re-entrant reads from inside the listener: must not deadlock (SPEC 4.6).
                  seenByListener.add(self.get().get("b"));
                  self.get().ttlRemaining("b");
                  self.get().size();
                })
            .build();
    self.set(cache);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          cache.put("a", "1");
          cache.put("b", "2"); // evicts a -> the listener reads b on the same thread
        });
    assertThat(seenByListener).containsExactly(Optional.of("2"));
    cache.close();
  }

  @Test
  void aThrowingListenerNeverBreaksTheCache() {
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder()
            .maximumSize(1)
            .removalListener(
                (k, v, c) -> {
                  throw new IllegalStateException("listener bug");
                })
            .build()) {
      cache.put("a", "1");
      cache.put("b", "2");
      assertThat(cache.get("b")).contains("2");
      assertThat(cache.size()).isEqualTo(1);
    }
  }

  @Test
  void removalsRunOnTheConfiguredExecutor() throws Exception {
    ExecutorService executor =
        Executors.newSingleThreadExecutor(r -> new Thread(r, "removal-executor"));
    ConcurrentLinkedQueue<String> threads = new ConcurrentLinkedQueue<>();
    CountDownLatch delivered = new CountDownLatch(1);
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder()
            .maximumSize(1)
            .removalExecutor(executor)
            .removalListener(
                (k, v, c) -> {
                  threads.add(Thread.currentThread().getName());
                  delivered.countDown();
                })
            .build()) {
      cache.put("a", "1");
      cache.put("b", "2");
      assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
      assertThat(threads).containsExactly("removal-executor");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void observersSeeEveryLookupWithItsOutcome() {
    ConcurrentLinkedQueue<String> seen = new ConcurrentLinkedQueue<>();
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder()
            .maximumSize(4)
            .accessObserver((k, hit) -> seen.add(k + ":" + hit))
            .accessObserver(
                (k, hit) -> {
                  throw new IllegalStateException("observer bug"); // must be contained
                })
            .build()) {
      cache.get("a");
      cache.put("a", "1");
      cache.get("a");
      cache.getOrLoad("b", k -> "loaded");
      cache.getOrLoad("b", k -> "unused");
      assertThat(seen).containsExactly("a:false", "a:true", "b:false", "b:true");
    }
  }

  @Test
  void hundredConcurrentLoadsOfOneKeyRunTheLoaderOnce() throws Exception {
    AtomicInteger loaderCalls = new AtomicInteger();
    int threads = 100;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().maximumSize(10).build()) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<String>> results = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  return cache.getOrLoad(
                      "drug:1",
                      k -> {
                        loaderCalls.incrementAndGet();
                        sleepQuietly(200);
                        return "aspirin";
                      });
                }));
      }
      start.countDown();
      for (Future<String> f : results) {
        assertThat(f.get(10, TimeUnit.SECONDS)).isEqualTo("aspirin");
      }
      assertThat(loaderCalls.get()).isEqualTo(1);
      CacheStats s = cache.stats();
      assertThat(s.loadSuccessCount()).isEqualTo(1);
      assertThat(s.requestCount()).isEqualTo(threads); // exactly one hit or miss per call
      assertThat(s.averageLoadPenaltyNanos()).isGreaterThan(0);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aFailingLoaderReachesEveryWaiterAndIsNotCached() throws Exception {
    AtomicInteger loaderCalls = new AtomicInteger();
    ExecutorService pool = Executors.newFixedThreadPool(8);
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().maximumSize(10).build()) {
      CountDownLatch start = new CountDownLatch(1);
      List<Future<Throwable>> results = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        results.add(
            pool.submit(
                () -> {
                  start.await();
                  try {
                    cache.getOrLoad(
                        "k",
                        k -> {
                          loaderCalls.incrementAndGet();
                          sleepQuietly(150);
                          throw new IllegalStateException("database down");
                        });
                    return null;
                  } catch (CacheLoadException e) {
                    return e;
                  }
                }));
      }
      start.countDown();
      for (Future<Throwable> f : results) {
        assertThat(f.get(10, TimeUnit.SECONDS))
            .isInstanceOf(CacheLoadException.class)
            .hasRootCauseInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("database down");
      }
      assertThat(loaderCalls.get()).isEqualTo(1);
      assertThat(cache.get("k")).isEmpty(); // failures are not cached
      assertThat(cache.stats().loadFailureCount()).isEqualTo(1);
      assertThat(cache.getOrLoad("k", k -> "recovered")).isEqualTo("recovered");
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void aLoaderReturningNullIsAFailure() {
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().maximumSize(10).build()) {
      assertThatThrownBy(() -> cache.getOrLoad("k", k -> null))
          .isInstanceOf(CacheLoadException.class)
          .hasMessageContaining("null");
      assertThat(cache.size()).isZero();
    }
  }

  @Test
  void aHitNeverCallsTheLoader() {
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().maximumSize(10).build()) {
      cache.put("k", "cached");
      assertThat(
              cache.getOrLoad(
                  "k",
                  k -> {
                    throw new AssertionError("loader must not run on a hit");
                  }))
          .isEqualTo("cached");
    }
  }

  @Test
  void loadedValuesGetTheDefaultTtl() {
    FakeTicker ticker = new FakeTicker();
    try (Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder()
            .maximumSize(10)
            .ticker(ticker)
            .defaultTtl(Duration.ofSeconds(5))
            .build()) {
      cache.getOrLoad("k", k -> "v");
      assertThat(cache.ttlRemaining("k")).contains(Duration.ofSeconds(5));
    }
  }

  @Test
  void closeStopsTheSweeperThread() throws Exception {
    Cache<String, String> cache =
        CacheBuilder.<String, String>newBuilder().name("closing").maximumSize(4).build();
    Thread sweeper =
        Thread.getAllStackTraces().keySet().stream()
            .filter(t -> t.getName().equals("cachelab-sweeper-closing"))
            .findFirst()
            .orElseThrow();
    assertThat(sweeper.isDaemon()).isTrue();
    cache.close();
    sweeper.join(5_000);
    assertThat(sweeper.isAlive()).isFalse();
    cache.put("still", "usable"); // entries remain usable after close
    assertThat(cache.get("still")).contains("usable");
  }

  private static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis); // simulates a slow database call inside the loader, not a TTL wait
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

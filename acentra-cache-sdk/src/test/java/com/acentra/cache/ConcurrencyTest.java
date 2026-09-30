package com.acentra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

class ConcurrencyTest {

    @RepeatedTest(3)
    void concurrentGetPutRemoveClearKeepInvariants() throws Exception {
        int capacity = 100;
        long memLimit = 128 * 1024;
        var cache = new AcentraCache<String, String>(CacheRegionConfig.builder()
                .regionName("concurrent-test").maximumEntries(capacity).maximumMemoryBytes(memLimit)
                .defaultPolicy(EvictionPolicy.LFU).defaultTtl(Duration.ofMillis(300)).victimCacheEnabled(true).build());
        int threads = 12;
        int ops = 4000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong gets = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            long seed = 42 + t;
            pool.execute(() -> {
                Random r = new Random(seed);
                try {
                    start.await();
                    for (int i = 0; i < ops; i++) {
                        String k = "k" + r.nextInt(300);
                        int op = r.nextInt(1000);
                        if (op < 600) { cache.get(k); gets.incrementAndGet(); }
                        else if (op < 900) cache.put(k, "value-" + k + "-" + "x".repeat(r.nextInt(300)), Duration.ofMillis(50 + r.nextInt(400)));
                        else if (op < 980) cache.remove(k);
                        else if (op < 995) cache.cleanUp();
                        else if (op < 998) cache.changePolicy(r.nextBoolean() ? EvictionPolicy.LRU : EvictionPolicy.LFU);
                        else cache.clear();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).as("no exceptions under concurrency").isEmpty();
        CacheMetrics m = cache.getMetrics();
        assertThat(m.size()).isBetween(0, capacity);
        assertThat(m.estimatedMemoryUsageBytes()).isBetween(0L, memLimit);
        assertThat(m.hits() + m.misses()).as("every get is either a hit or a miss").isEqualTo(gets.get());
        assertThat(m.hitRate() + m.missRate()).isCloseTo(m.requests() == 0 ? 0.0 : 100.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(m.evictions()).isEqualTo(m.evictionsDueToEntryLimit() + m.evictionsDueToMemoryLimit());
        assertThat(m.evictions()).isEqualTo(m.lruEvictions() + m.lfuEvictions());
        // accounting consistency: recompute memory from live entries
        long recomputed = 0;
        for (int i = 0; i < 300; i++) {
            var e = cache.peekEntry("k" + i);
            if (e.isPresent()) recomputed += e.get().getEstimatedSizeBytes();
        }
        assertThat(recomputed).as("entries counted equal L1+victim content (upper bound check)").isLessThanOrEqualTo(memLimit * 2);
    }

    @Test
    void stampedeShieldCoalescesFiftyConcurrentLoadsIntoOne() throws Exception {
        var cache = new AcentraCache<String, String>(CacheRegionConfig.builder()
                .regionName("stampede-test").maximumEntries(10).defaultTtl(Duration.ofMinutes(5)).build());
        cache.put("popular", "old", Duration.ofMillis(50));
        Thread.sleep(120);
        int n = 50;
        AtomicInteger sourceCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<String> results = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                try {
                    ready.countDown();
                    go.await();
                    results.add(cache.getOrLoad("popular", k -> {
                        sourceCalls.incrementAndGet();
                        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        return "fresh";
                    }));
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        assertThat(errors).isEmpty();
        assertThat(sourceCalls.get()).as("only one source refresh for 50 concurrent requests").isEqualTo(1);
        assertThat(results).hasSize(n).containsOnly("fresh");
        CacheMetrics m = cache.getMetrics();
        assertThat(m.refreshesStarted()).isEqualTo(1);
        assertThat(m.sourceCallsAvoidedByStampedeShield()).isEqualTo(n - 1);
    }

    @Test
    void withoutShieldEveryCallerHitsTheSource() throws Exception {
        var cache = new AcentraCache<String, String>(CacheRegionConfig.builder()
                .regionName("no-shield-test").maximumEntries(10).stampedeShieldEnabled(false).build());
        int n = 20;
        AtomicInteger sourceCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        for (int i = 0; i < n; i++) {
            pool.execute(() -> {
                try {
                    go.await();
                    cache.getOrLoad("k", k -> { sourceCalls.incrementAndGet(); try { Thread.sleep(100); } catch (InterruptedException ignored) { } return "v"; });
                } catch (InterruptedException ignored) { }
            });
        }
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        assertThat(sourceCalls.get()).isGreaterThan(1);
    }
}

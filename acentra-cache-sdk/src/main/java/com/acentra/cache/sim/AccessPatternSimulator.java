package com.acentra.cache.sim;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheMetrics;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.EvictionPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Runs the sample access patterns A to G against real {@link AcentraCache} instances using synthetic keys only.
 * The cache factory is pluggable so the telemetry service can bind the caches to its own in-process telemetry.
 */
public final class AccessPatternSimulator {

    /** What the factory must build. {@code clock == null} means the system clock. */
    public record CacheSpec(String regionName, EvictionPolicy policy, int maximumEntries, long maximumMemoryBytes,
                            Duration defaultTtl, boolean stampedeShield, Clock clock) {
        public CacheSpec(String regionName, EvictionPolicy policy, int maximumEntries, long maximumMemoryBytes, Duration defaultTtl) {
            this(regionName, policy, maximumEntries, maximumMemoryBytes, defaultTtl, true, null);
        }
    }

    public record PatternResult(String id, String name, String description, long requests, long hits, long misses,
                                double hitRate, long evictions, long expirations, boolean expectationMet, String detail) {}

    public record PolicyComparison(String pattern, PatternResult lru, PatternResult lfu, String winner, String interpretation) {}

    private final Function<CacheSpec, AcentraCache<String, String>> factory;

    public AccessPatternSimulator(Function<CacheSpec, AcentraCache<String, String>> factory) {
        this.factory = factory;
    }

    /** Plain caches with no telemetry; every routine event is recorded locally. */
    public static AccessPatternSimulator standalone() {
        return new AccessPatternSimulator(spec -> {
            CacheRegionConfig.Builder b = CacheRegionConfig.builder()
                    .regionName(spec.regionName())
                    .defaultPolicy(spec.policy())
                    .maximumEntries(spec.maximumEntries())
                    .maximumMemoryBytes(spec.maximumMemoryBytes())
                    .defaultTtl(spec.defaultTtl())
                    .stampedeShieldEnabled(spec.stampedeShield())
                    .routineEventSampling(1);
            if (spec.clock() != null) b.clock(spec.clock());
            return new AcentraCache<>(b.build());
        });
    }

    // ---- key streams ------------------------------------------------------------------------------------------------

    /** Pattern A from the spec: A, B, C, A, D, E, D, E, F, G. */
    public static List<String> patternASequence() {
        return List.of("A", "B", "C", "A", "D", "E", "D", "E", "F", "G");
    }

    /** Pattern B from the spec: A, A, A, A, B, B, B, C, D, E, A, B. */
    public static List<String> patternBSequence() {
        return List.of("A", "A", "A", "A", "B", "B", "B", "C", "D", "E", "A", "B");
    }

    /**
     * LRU-friendly stream: the hot set moves on every phase of 150 requests, so recently used keys matter and keys that were
     * popular earlier are dead weight that LFU keeps protecting.
     */
    public static List<String> changingAccessKeys(int requests, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            int phase = i / 150;
            out.add("p" + phase + "-k" + r.nextInt(5));
        }
        return out;
    }

    /** LFU-friendly stream: four keys receive half of all requests; the rest is a one-off scan over many cold keys. */
    public static List<String> stablePopularityKeys(int requests, long seed) {
        Random r = new Random(seed);
        List<String> out = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            if (r.nextInt(100) < 50) out.add("hot-" + r.nextInt(4));
            else out.add("cold-" + r.nextInt(400));
        }
        return out;
    }

    // ---- replay / comparison --------------------------------------------------------------------------------------

    /** Read-through replay: get; on a miss "load from the source" (synthetic) and put. */
    public PatternResult replay(String id, String name, String description, CacheSpec spec, List<String> keys) {
        AcentraCache<String, String> cache = factory.apply(spec);
        for (String k : keys) {
            if (cache.get(k).isEmpty()) cache.put(k, valueFor(k), spec.defaultTtl());
        }
        CacheMetrics m = cache.getMetrics();
        return new PatternResult(id, name, description, keys.size(), m.hits(), m.misses(), round2(m.hitRate()), m.evictions(),
                m.expirations(), true, spec.policy() + " with capacity " + spec.maximumEntries() + ": " + m.hits() + " hits / "
                + m.misses() + " misses, " + m.evictions() + " evictions");
    }

    public PolicyComparison compare(String regionPrefix, String patternId, String patternName, String interpretation,
                                    List<String> keys, int capacity) {
        Duration ttl = Duration.ofMinutes(30);
        PatternResult lru = replay(patternId, patternName, interpretation,
                new CacheSpec(regionPrefix + "-lru", EvictionPolicy.LRU, capacity, 256L * 1024 * 1024, ttl), keys);
        PatternResult lfu = replay(patternId, patternName, interpretation,
                new CacheSpec(regionPrefix + "-lfu", EvictionPolicy.LFU, capacity, 256L * 1024 * 1024, ttl), keys);
        String winner = Math.abs(lru.hitRate() - lfu.hitRate()) < 0.05 ? "TIE" : lru.hitRate() > lfu.hitRate() ? "LRU" : "LFU";
        return new PolicyComparison(patternName, lru, lfu, winner, interpretation);
    }

    // ---- pattern C: TTL expiration ----------------------------------------------------------------------------------

    /** Put with TTL 500 ms, wait 600 ms, get: must record a MISS and an EXPIRED event. */
    public PatternResult ttlExpiration(String region) throws InterruptedException {
        AcentraCache<String, String> cache = factory.apply(
                new CacheSpec(region, EvictionPolicy.LRU, 100, 64L * 1024 * 1024, Duration.ofMinutes(5)));
        cache.put("ttl-key", "value", Duration.ofMillis(500));
        boolean hitBefore = cache.get("ttl-key").isPresent();
        Thread.sleep(600);
        boolean hitAfter = cache.get("ttl-key").isPresent();
        CacheMetrics m = cache.getMetrics();
        boolean ok = hitBefore && !hitAfter && m.expirations() >= 1 && m.misses() >= 1;
        return new PatternResult("C", "TTL expiration", "Put with TTL 500 ms, wait 600 ms, get", 2, m.hits(), m.misses(),
                round2(m.hitRate()), m.evictions(), m.expirations(), ok,
                "before expiry: " + (hitBefore ? "HIT" : "MISS") + ", after 600 ms: " + (hitAfter ? "HIT" : "MISS (EXPIRED recorded)"));
    }

    // ---- pattern D: capacity eviction -----------------------------------------------------------------------------

    /** Capacity 3; put A, B, C; access A; put D. Under LRU, B must be evicted. */
    public PatternResult capacityEviction(String region, EvictionPolicy policy) {
        AcentraCache<String, String> cache = factory.apply(new CacheSpec(region, policy, 3, 64L * 1024 * 1024, Duration.ofMinutes(5)));
        for (String k : List.of("A", "B", "C")) cache.put(k, valueFor(k));
        cache.get("A");
        cache.put("D", valueFor("D"));
        List<String> present = new ArrayList<>();
        for (String k : List.of("A", "B", "C", "D")) if (cache.peekEntry(k).isPresent()) present.add(k);
        boolean ok = policy != EvictionPolicy.LRU || (!present.contains("B") && present.size() == 3);
        CacheMetrics m = cache.getMetrics();
        return new PatternResult("D", "Capacity eviction", "Capacity 3: put A,B,C; get A; put D", 1, m.hits(), m.misses(),
                round2(m.hitRate()), m.evictions(), m.expirations(), ok, policy + " kept " + present + " (capacity 3)");
    }

    // ---- pattern E: memory eviction -------------------------------------------------------------------------------

    /** Small memory limit plus several large synthetic values must produce memory-limit evictions. */
    public PatternResult memoryEviction(String region) {
        long limit = 8 * 1024;
        AcentraCache<String, String> cache = factory.apply(new CacheSpec(region, EvictionPolicy.LRU, 10_000, limit, Duration.ofMinutes(5)));
        String big = "x".repeat(1500);
        for (int i = 0; i < 12; i++) cache.put("big-" + i, big);
        CacheMetrics m = cache.getMetrics();
        boolean ok = m.evictionsDueToMemoryLimit() > 0 && m.estimatedMemoryUsageBytes() <= limit;
        return new PatternResult("E", "Memory eviction", "8 KB memory limit, 12 values of ~1.5 KB", 12, m.hits(), m.misses(),
                round2(m.hitRate()), m.evictions(), m.expirations(), ok, m.evictionsDueToMemoryLimit() + " memory-limit evictions; estimated usage "
                + m.estimatedMemoryUsageBytes() + " of " + limit + " bytes");
    }

    // ---- pattern F: concurrent access -----------------------------------------------------------------------------

    public PatternResult concurrentAccess(String region, int threads, int opsPerThread) throws InterruptedException {
        int capacity = 200;
        long memLimit = 512L * 1024;
        AcentraCache<String, String> cache = factory.apply(new CacheSpec(region, EvictionPolicy.LFU, capacity, memLimit, Duration.ofMillis(800)));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicLong gets = new AtomicLong();
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int t = 0; t < threads; t++) {
            final long seed = 1000 + t;
            pool.execute(() -> {
                Random r = new Random(seed);
                try {
                    start.await();
                    for (int i = 0; i < opsPerThread; i++) {
                        String k = "k" + r.nextInt(400);
                        int op = r.nextInt(100);
                        if (op < 65) { cache.get(k); gets.incrementAndGet(); }
                        else if (op < 95) cache.put(k, valueFor(k));
                        else if (op < 99) cache.remove(k);
                        else cache.cleanUp();
                    }
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        boolean finished = pool.awaitTermination(60, TimeUnit.SECONDS);
        CacheMetrics m = cache.getMetrics();
        boolean ok = finished && errors.isEmpty() && m.size() <= capacity && m.estimatedMemoryUsageBytes() <= memLimit
                && m.hits() + m.misses() == gets.get();
        return new PatternResult("F", "Concurrent access", threads + " threads x " + opsPerThread + " mixed operations",
                gets.get(), m.hits(), m.misses(), round2(m.hitRate()), m.evictions(), m.expirations(), ok,
                "errors=" + errors.size() + ", size=" + m.size() + "/" + capacity + ", hits+misses=" + (m.hits() + m.misses())
                        + " vs gets=" + gets.get());
    }

    // ---- pattern G: stampede --------------------------------------------------------------------------------------

    /** Expire one popular key, fire N concurrent getOrLoad calls: with the shield there is exactly one source refresh. */
    public PatternResult stampede(String region, int concurrent) throws InterruptedException {
        AcentraCache<String, String> cache = factory.apply(
                new CacheSpec(region, EvictionPolicy.LFU, 100, 64L * 1024 * 1024, Duration.ofMinutes(5), true, null));
        cache.put("popular", "old-value", Duration.ofMillis(100));
        Thread.sleep(160);
        AtomicInteger sourceCalls = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        CountDownLatch ready = new CountDownLatch(concurrent);
        CountDownLatch go = new CountDownLatch(1);
        ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
        for (int i = 0; i < concurrent; i++) {
            pool.execute(() -> {
                try {
                    ready.countDown();
                    go.await();
                    cache.getOrLoad("popular", Duration.ofMinutes(5), k -> {
                        sourceCalls.incrementAndGet();
                        try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        return "fresh-value";
                    });
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        ready.await();
        go.countDown();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        CacheMetrics m = cache.getMetrics();
        boolean ok = errors.isEmpty() && sourceCalls.get() == 1;
        return new PatternResult("G", "Cache stampede", concurrent + " concurrent requests for one expired key", concurrent,
                m.hits(), m.misses(), round2(m.hitRate()), m.evictions(), m.expirations(), ok,
                concurrent + " concurrent requests produced " + sourceCalls.get() + " source call(s); coalesced="
                        + m.concurrentRequestsCoalesced() + ", avoided=" + m.sourceCallsAvoidedByStampedeShield());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    /** Synthetic ~200-byte value; never real data. */
    static String valueFor(String key) {
        char[] pad = new char[150];
        Arrays.fill(pad, '.');
        return "synthetic:" + key + ":" + new String(pad);
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

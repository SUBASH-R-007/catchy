package com.acentra.cache;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.cache.telemetry.TelemetryEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Thread-safe in-memory cache for one region.
 *
 * <ul>
 *   <li><b>TTL is independent of eviction.</b> An entry is a hit only if it exists and has not expired; LRU/LFU are
 *       consulted only when capacity (entries or estimated memory) must be reclaimed.</li>
 *   <li><b>LRU</b>: evicts the least recently accessed entry. <b>LFU</b>: evicts the lowest hit frequency; ties are
 *       broken by least recent access. Both indexes are always maintained, so the policy can be switched at runtime.</li>
 *   <li>One {@link ReentrantLock} guards the map, both indexes, the expiry heap, counters, events and policy, which
 *       keeps the invariants simple and verifiable.</li>
 *   <li>Memory figures are <b>estimated cache memory usage</b>, not exact JVM heap allocation.</li>
 *   <li>Only key <i>fingerprints</i> ever leave this class (events/telemetry); raw keys and values never do.</li>
 * </ul>
 */
public final class AcentraCache<K, V> {

    private static final int MAX_EXPIRED_EVENTS_PER_PURGE = 20;

    private enum EvictCause { ENTRY_LIMIT, MEMORY_LIMIT }

    private record Lookup<V>(boolean hit, V value, boolean hasStale, V stale, long staleUntil) {
        static <V> Lookup<V> hit(V v) { return new Lookup<>(true, v, false, null, 0); }
        static <V> Lookup<V> miss() { return new Lookup<>(false, null, false, null, 0); }
    }

    private static final class Refresh<V> {
        final CompletableFuture<V> future = new CompletableFuture<>();
        final boolean hasStale;
        final V stale;
        final long staleUntil;

        Refresh(boolean hasStale, V stale, long staleUntil) {
            this.hasStale = hasStale;
            this.stale = stale;
            this.staleUntil = staleUntil;
        }
    }

    private static final class RefreshPool {
        static final ExecutorService POOL = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                r -> {
                    Thread t = new Thread(r, "acentra-cache-refresh");
                    t.setDaemon(true);
                    return t;
                }, new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private final CacheRegionConfig config;
    private final String region;
    private final String app;
    private final String env;
    private final Clock clock;
    private final CacheKeySanitizer sanitizer;
    private final CacheMemoryEstimator estimator;
    private final TelemetryClient telemetry;
    private final boolean victimEnabled;
    private final boolean swrAllowed;

    private final ReentrantLock lock = new ReentrantLock();

    // ---- state guarded by `lock` --------------------------------------------------------------------------------
    private final HashMap<K, CacheEntry<V>> map = new HashMap<>();
    private final LruList<V> lru = new LruList<>();
    private final LfuIndex<V> lfu = new LfuIndex<>();
    private final ExpiryQueue expiry = new ExpiryQueue();
    private final LinkedHashMap<K, CacheEntry<V>> victim = new LinkedHashMap<>();
    private final RollingWindow window = new RollingWindow();
    private final ShadowSimulator shadow;
    private final ArrayDeque<CacheDecisionEvent> events = new ArrayDeque<>();
    private final ConcurrentHashMap<K, Refresh<V>> inflight = new ConcurrentHashMap<>();

    private int maximumEntries;
    private long maximumMemoryBytes;
    private Duration defaultTtl;
    private EvictionPolicy policy;
    private Instant lastPolicyChangeAt;
    private long memoryBytes;
    private long victimBytes;
    private long eventSeq;
    private long routineCounter;

    private long hits, misses, puts, removes, clears, evictions, expirations;
    private long lruEvictions, lfuEvictions, entryLimitEvictions, memoryLimitEvictions;
    private long l1Hits, victimHits, sourceMisses, victimEvictions, sourceCallsAvoided;
    private long refreshesStarted, coalesced, stampedeAvoided, refreshFailures;
    private long sourceCalls, sourceErrors, staleServed, staleCorrections, staleViolations;
    private long getOps, getNanos, putOps, putNanos;

    public AcentraCache(CacheRegionConfig config) {
        this.config = Objects.requireNonNull(config, "config");
        this.region = config.regionName();
        this.app = config.applicationName();
        this.env = config.environment();
        this.clock = config.clock();
        this.sanitizer = config.keySanitizer();
        this.estimator = config.memoryEstimator();
        this.telemetry = config.telemetryClient();
        this.victimEnabled = config.victimCacheEnabled();
        this.swrAllowed = config.staleWhileRevalidate() && config.riskLevel() == CacheRiskLevel.LOW;
        this.maximumEntries = config.maximumEntries();
        this.maximumMemoryBytes = config.maximumMemoryBytes();
        this.defaultTtl = config.defaultTtl();
        this.policy = config.defaultPolicy();
        this.shadow = config.shadowEnabled() ? new ShadowSimulator(config.shadowWindowSize()) : null;
    }

    // =================================================================================================================
    // Public API
    // =================================================================================================================

    /** Returns the value only if the entry exists and has not expired. Expired entries are removed, never returned. */
    public Optional<V> get(K key) {
        Objects.requireNonNull(key, "key");
        long t0 = System.nanoTime();
        lock.lock();
        try {
            Lookup<V> l = lookup(key, t0);
            getOps++;
            getNanos += System.nanoTime() - t0;
            return l.hit() ? Optional.of(l.value()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public void put(K key, V value, Duration ttl) {
        put(key, value, ttl, null);
    }

    public void put(K key, V value) {
        put(key, value, null, null);
    }

    /** Stores a value with its own TTL ({@code null} = region default). */
    public void put(K key, V value, Duration ttl, String sourceVersion) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (ttl != null && (ttl.isZero() || ttl.isNegative())) throw new IllegalArgumentException("ttl must be positive");
        long size = estimator.estimateBytes(key, value);
        String fp = sanitizer.fingerprint(region, key);
        long t0 = System.nanoTime();
        lock.lock();
        try {
            long now = clock.millis();
            purgeExpired(now, t0);
            Duration effective = ttl != null ? ttl : defaultTtl;
            long ttlMs = Math.max(1, effective.toMillis());
            long expiresAt = ttlMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + ttlMs;
            int sizeBefore = map.size();
            long memBefore = memoryBytes;

            CacheEntry<V> old = map.get(key);
            CacheEntry<V> oldVictim = victimEnabled ? victim.get(key) : null;

            if (size > maximumMemoryBytes) {
                if (old != null) { detachL1(old); old.live = false; }
                if (oldVictim != null) dropVictimEntry(oldVictim);
                emit(CacheAction.PUT, fp, "Rejected: estimated entry size " + Humanize.bytes(size)
                        + " exceeds the region memory limit " + Humanize.bytes(maximumMemoryBytes)
                        + "; any previous value for this key was removed.", null, sizeBefore, memBefore, false,
                        EventSeverity.WARN, t0, now);
                return;
            }

            long freq = 0;
            boolean overwrite = false;
            if (old != null) {
                freq = old.frequency;
                detachL1(old);
                old.live = false;
                overwrite = true;
            }
            if (oldVictim != null) {
                freq = Math.max(freq, oldVictim.frequency);
                dropVictimEntry(oldVictim);
                overwrite = true;
            }

            while (map.size() >= maximumEntries && evictOne(EvictCause.ENTRY_LIMIT, now, t0)) { /* evict */ }
            while (memoryBytes + size > maximumMemoryBytes && evictOne(EvictCause.MEMORY_LIMIT, now, t0)) { /* evict */ }

            CacheEntry<V> e = new CacheEntry<>();
            e.key = key;
            e.value = value;
            e.createdAt = now;
            e.expiresAt = expiresAt;
            e.frequency = freq;
            e.lastAccessTime = now;
            e.estimatedSizeBytes = size;
            e.riskLevel = config.riskLevel();
            e.cacheRegion = region;
            e.sourceVersion = sourceVersion;
            e.keyFingerprint = fp;
            e.live = true;
            attachL1(e);
            expiry.add(e);
            expiry.maybeCompact(map.values(), victim.values());
            puts++;
            window.add(RollingWindow.PUTS, now);
            putOps++;
            putNanos += System.nanoTime() - t0;
            if (routineSampled()) {
                emit(CacheAction.PUT, fp, (overwrite ? "Replaced existing entry" : "Stored new entry") + " (TTL "
                        + Humanize.millis(ttlMs) + ", estimated size " + Humanize.bytes(size) + ")", e, sizeBefore,
                        memBefore, false, EventSeverity.INFO, t0, now);
            }
        } finally {
            lock.unlock();
        }
    }

    /** @return true if a live (non-expired) entry was removed. */
    public boolean remove(K key) {
        Objects.requireNonNull(key, "key");
        long t0 = System.nanoTime();
        lock.lock();
        try {
            long now = clock.millis();
            CacheEntry<V> e = map.get(key);
            if (e == null && victimEnabled) e = victim.get(key);
            if (e == null) return false;
            if (e.expiresAt <= now) {
                expireEntry(e, now, t0, "Entry had already expired; removed during explicit remove", true);
                return false;
            }
            int sizeBefore = map.size();
            long memBefore = memoryBytes;
            removeAnyLayer(e);
            removes++;
            emit(CacheAction.REMOVE, e.keyFingerprint, "Explicitly removed by caller", e, sizeBefore, memBefore, false,
                    EventSeverity.INFO, t0, now);
            return true;
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        long t0 = System.nanoTime();
        lock.lock();
        try {
            long now = clock.millis();
            int sizeBefore = map.size();
            long memBefore = memoryBytes;
            for (CacheEntry<V> e : map.values()) e.live = false;
            for (CacheEntry<V> e : victim.values()) e.live = false;
            map.clear();
            victim.clear();
            lru.clear();
            lfu.clear();
            expiry.clear();
            memoryBytes = 0;
            victimBytes = 0;
            clears++;
            emit(CacheAction.CLEAR, null, "Cache region cleared (" + sizeBefore + " entries, "
                    + Humanize.bytes(memBefore) + " released)", null, sizeBefore, memBefore, false, EventSeverity.INFO, t0, now);
        } finally {
            lock.unlock();
        }
    }

    /** Removes every expired entry now. Safe to call periodically. @return number of entries removed. */
    public int cleanUp() {
        lock.lock();
        try {
            return purgeExpired(clock.millis(), System.nanoTime());
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return map.size();
        } finally {
            lock.unlock();
        }
    }

    /** Estimated (not exact) memory used by the primary layer. */
    public long estimatedMemoryUsageBytes() {
        lock.lock();
        try {
            return memoryBytes;
        } finally {
            lock.unlock();
        }
    }

    public EvictionPolicy getCurrentPolicy() {
        lock.lock();
        try {
            return policy;
        } finally {
            lock.unlock();
        }
    }

    public void changePolicy(EvictionPolicy newPolicy) {
        changePolicy(newPolicy, "Policy changed by engineer");
    }

    public void changePolicy(EvictionPolicy newPolicy, String why) {
        Objects.requireNonNull(newPolicy, "policy");
        long t0 = System.nanoTime();
        lock.lock();
        try {
            if (newPolicy == policy) return;
            long now = clock.millis();
            EvictionPolicy old = policy;
            policy = newPolicy;
            lastPolicyChangeAt = Instant.ofEpochMilli(now);
            emit(CacheAction.POLICY_CHANGED, null, why + ": " + old + " -> " + newPolicy
                    + ". Existing entries keep their recency/frequency metadata.", null, map.size(), memoryBytes, false,
                    EventSeverity.INFO, t0, now);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Applies engineer-approved tuning. Shrinking evicts immediately according to the active policy.
     * Any argument may be null (unchanged).
     */
    public void reconfigure(Integer newMaximumEntries, Long newMaximumMemoryBytes, Duration newDefaultTtl, String why) {
        long t0 = System.nanoTime();
        lock.lock();
        try {
            long now = clock.millis();
            int sizeBefore = map.size();
            long memBefore = memoryBytes;
            List<String> changes = new ArrayList<>();
            if (newMaximumEntries != null && newMaximumEntries >= 1 && newMaximumEntries != maximumEntries) {
                changes.add("maximumEntries " + maximumEntries + " -> " + newMaximumEntries);
                maximumEntries = newMaximumEntries;
            }
            if (newMaximumMemoryBytes != null && newMaximumMemoryBytes >= 1 && newMaximumMemoryBytes != maximumMemoryBytes) {
                changes.add("maximumMemory " + Humanize.bytes(maximumMemoryBytes) + " -> " + Humanize.bytes(newMaximumMemoryBytes));
                maximumMemoryBytes = newMaximumMemoryBytes;
            }
            if (newDefaultTtl != null && !newDefaultTtl.isZero() && !newDefaultTtl.isNegative()
                    && !newDefaultTtl.equals(defaultTtl)) {
                changes.add("defaultTtl " + Humanize.millis(defaultTtl.toMillis()) + " -> " + Humanize.millis(newDefaultTtl.toMillis()));
                defaultTtl = newDefaultTtl;
            }
            if (changes.isEmpty()) return;
            while (map.size() > maximumEntries && evictOne(EvictCause.ENTRY_LIMIT, now, t0)) { /* shrink */ }
            while (memoryBytes > maximumMemoryBytes && evictOne(EvictCause.MEMORY_LIMIT, now, t0)) { /* shrink */ }
            trimVictim(now, t0);
            emit(CacheAction.CONFIG_CHANGED, null, (why == null ? "Configuration changed" : why) + ": "
                    + String.join("; ", changes), null, sizeBefore, memBefore, false, EventSeverity.INFO, t0, now);
        } finally {
            lock.unlock();
        }
    }

    /** Immutable copy of a live entry's metadata without touching hit/miss counters or LRU/LFU order. */
    public Optional<CacheEntry<V>> peekEntry(K key) {
        Objects.requireNonNull(key, "key");
        lock.lock();
        try {
            long now = clock.millis();
            CacheEntry<V> e = map.get(key);
            if (e == null && victimEnabled) e = victim.get(key);
            return e != null && e.expiresAt > now ? Optional.of(e.copy()) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public CacheMetrics getMetrics() {
        lock.lock();
        try {
            return metricsLocked();
        } finally {
            lock.unlock();
        }
    }

    /** Latest decision events (bounded by {@code decisionEventCapacity}), oldest first. */
    public List<CacheDecisionEvent> getDecisionEvents() {
        lock.lock();
        try {
            return List.copyOf(events);
        } finally {
            lock.unlock();
        }
    }

    public CacheRegionConfig getConfig() {
        lock.lock();
        try {
            return config.toBuilder()
                    .maximumEntries(maximumEntries)
                    .maximumMemoryBytes(maximumMemoryBytes)
                    .defaultTtl(defaultTtl)
                    .defaultPolicy(policy)
                    .build();
        } finally {
            lock.unlock();
        }
    }

    public CacheHealthReport getHealth() {
        lock.lock();
        try {
            return healthLocked();
        } finally {
            lock.unlock();
        }
    }

    public CachePolicyRecommendation getRecommendation() {
        return getRecommendation(PolicyAdvisor.Settings.defaults());
    }

    public CachePolicyRecommendation getRecommendation(PolicyAdvisor.Settings settings) {
        lock.lock();
        try {
            ShadowSimulator.Stats s = shadow == null ? new ShadowSimulator.Stats(0, 0, 0, 0, 0) : shadow.stats();
            return PolicyAdvisor.evaluate(new PolicyAdvisor.Input(policy, s.windowRequests(), s.lruHitRate(), s.lfuHitRate(),
                    s.topKeyConcentrationPercent(), lastPolicyChangeAt, Instant.ofEpochMilli(clock.millis())), settings);
        } finally {
            lock.unlock();
        }
    }

    /** Builds the telemetry snapshot (cumulative counters) for this region. */
    public RegionSnapshot snapshot() {
        lock.lock();
        try {
            long now = clock.millis();
            CacheMetrics m = metricsLocked();
            RegionSnapshot.RecentWindow rw = new RegionSnapshot.RecentWindow(RollingWindow.MINUTES,
                    window.sum(RollingWindow.HITS, now), window.sum(RollingWindow.MISSES, now),
                    window.sum(RollingWindow.PUTS, now), window.sum(RollingWindow.EVICTIONS, now),
                    window.sum(RollingWindow.EXPIRATIONS, now));
            RegionSnapshot.Shadow sh = null;
            if (shadow != null) {
                ShadowSimulator.Stats s = shadow.stats();
                if (s.windowRequests() > 0) {
                    sh = new RegionSnapshot.Shadow(s.requests(), s.windowRequests(), s.lruHitRate(), s.lfuHitRate(),
                            s.topKeyConcentrationPercent());
                }
            }
            return new RegionSnapshot(Instant.ofEpochMilli(now), region, config.riskLevel(), policy, m.hits(), m.misses(),
                    m.puts(), m.removes(), m.clears(), m.evictions(), m.expirations(), m.size(), m.capacity(),
                    m.estimatedMemoryUsageBytes(), m.maximumMemoryBytes(), m.lruEvictions(), m.lfuEvictions(),
                    m.evictionsDueToEntryLimit(), m.evictionsDueToMemoryLimit(), m.averageGetLatencyMs(),
                    m.averagePutLatencyMs(), m.sourceCallsAvoided(), m.telemetryEventsSent(), m.telemetryEventsFailed(),
                    m.telemetryFailureStreak(), m.l1Hits(), m.victimHits(), m.sourceMisses(), m.victimEvictions(),
                    m.victimEnabled(), m.victimSize(), m.victimCapacity(), m.refreshesStarted(),
                    m.concurrentRequestsCoalesced(), m.sourceCallsAvoidedByStampedeShield(), m.refreshFailures(),
                    m.sourceCalls(), m.sourceErrors(), m.staleServed(), m.staleCorrections(), m.staleDataViolations(),
                    defaultTtl.toMillis(), lastPolicyChangeAt, rw, sh);
        } finally {
            lock.unlock();
        }
    }

    /** Lets an application report an observed stale-data policy violation (feeds health scoring). */
    public void recordStaleDataViolation(String why) {
        lock.lock();
        try {
            staleViolations++;
            emit(CacheAction.SOURCE_VALIDATED, null, "Stale-data policy violation reported: "
                    + (why == null ? "unspecified" : why), null, map.size(), memoryBytes, false, EventSeverity.CRITICAL,
                    System.nanoTime(), clock.millis());
        } finally {
            lock.unlock();
        }
    }

    // ---- read-through with Cache Stampede Shield -----------------------------------------------------------------

    public V getOrLoad(K key, Function<? super K, ? extends V> loader) {
        return getOrLoad(key, null, loader);
    }

    /**
     * Returns the cached value or loads it from the source. Concurrent callers for the same key share <b>one</b> load
     * (single-flight). For LOW-risk regions with {@code staleWhileRevalidate}, a just-expired value may be served while
     * one background refresh runs. HIGH/CRITICAL regions never serve stale data: callers wait for the source.
     */
    public V getOrLoad(K key, Duration ttl, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        long t0 = System.nanoTime();
        Lookup<V> l;
        lock.lock();
        try {
            l = lookup(key, t0);
            getOps++;
            getNanos += System.nanoTime() - t0;
        } finally {
            lock.unlock();
        }
        if (l.hit()) return l.value();
        return loadSingleFlight(key, ttl != null ? ttl : currentDefaultTtl(), loader, l, false);
    }

    /**
     * Mandatory source validation for risky regions (e.g. authorization decisions): always asks the source, corrects the
     * cache, and records whether the cached value had drifted. Use the cache for preliminary display only.
     */
    public V requireFreshFromSource(K key, Duration ttl, Function<? super K, ? extends V> loader) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Optional<V> before = peekValid(key);
        V fresh = loadSingleFlight(key, ttl != null ? ttl : currentDefaultTtl(), loader, Lookup.miss(), true);
        lock.lock();
        try {
            boolean drift = before.isPresent() && !Objects.equals(before.get(), fresh);
            if (drift) staleCorrections++;
            emit(CacheAction.SOURCE_VALIDATED, sanitizer.fingerprint(region, key),
                    drift ? "Drift detected: cached value differed from the source; cache corrected before the final action."
                            : before.isPresent() ? "Cached value matched the source." : "No cached value; loaded from the source.",
                    null, map.size(), memoryBytes, true, drift ? EventSeverity.WARN : EventSeverity.INFO, 0, clock.millis());
        } finally {
            lock.unlock();
        }
        return fresh;
    }

    // =================================================================================================================
    // Internals (all require `lock` unless stated)
    // =================================================================================================================

    private Duration currentDefaultTtl() {
        lock.lock();
        try {
            return defaultTtl;
        } finally {
            lock.unlock();
        }
    }

    private Optional<V> peekValid(K key) {
        lock.lock();
        try {
            long now = clock.millis();
            CacheEntry<V> e = map.get(key);
            if (e == null && victimEnabled) e = victim.get(key);
            return e != null && e.expiresAt > now ? Optional.of(e.value) : Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    private Lookup<V> lookup(K key, long t0) {
        long now = clock.millis();
        recordShadow(key, now);
        CacheEntry<V> e = map.get(key);
        if (e == null) {
            CacheEntry<V> v = victimEnabled ? victim.get(key) : null;
            if (v != null) {
                if (v.expiresAt <= now) {
                    expireEntry(v, now, t0, "Entry expired in the victim cache (TTL elapsed "
                            + Humanize.millis(now - v.expiresAt) + " ago); removed, not returned", true);
                } else {
                    int sizeBefore = map.size();
                    long memBefore = memoryBytes;
                    dropVictimEntryKeepLive(v);
                    if (v.estimatedSizeBytes > maximumMemoryBytes) {
                        v.live = false;
                    } else {
                        while (map.size() >= maximumEntries && evictOne(EvictCause.ENTRY_LIMIT, now, t0)) { /* make room */ }
                        while (memoryBytes + v.estimatedSizeBytes > maximumMemoryBytes
                                && evictOne(EvictCause.MEMORY_LIMIT, now, t0)) { /* make room */ }
                        v.frequency++;
                        attachL1(v);
                    }
                    hits++;
                    victimHits++;
                    sourceCallsAvoided++;
                    window.add(RollingWindow.HITS, now);
                    emit(CacheAction.VICTIM_HIT, v.keyFingerprint, "Missing from L1 but found in the victim cache; promoted back to L1 "
                            + "with its original expiry (remaining TTL " + Humanize.millis(Math.max(0, v.expiresAt - now)) + ")",
                            v, sizeBefore, memBefore, true, EventSeverity.INFO, t0, now);
                    v.lastAccessTime = now;
                    return Lookup.hit(v.value);
                }
            }
            misses++;
            sourceMisses++;
            window.add(RollingWindow.MISSES, now);
            emit(CacheAction.MISS, sanitizer.fingerprint(region, key),
                    victimEnabled ? "Not present in L1 or the victim cache (source miss)" : "Key not present", null,
                    map.size(), memoryBytes, false, EventSeverity.INFO, t0, now);
            return Lookup.miss();
        }
        if (e.expiresAt <= now) {
            V staleValue = e.value;
            long staleUntil = e.expiresAt + config.staleGrace().toMillis();
            boolean staleOk = swrAllowed && now <= staleUntil;
            expireEntry(e, now, t0, "TTL elapsed " + Humanize.millis(now - e.expiresAt)
                    + " ago; entry removed and not returned as a hit", true);
            misses++;
            sourceMisses++;
            window.add(RollingWindow.MISSES, now);
            emit(CacheAction.MISS, e.keyFingerprint, "Entry existed but had expired (TTL is checked before LRU/LFU)", null,
                    map.size(), memoryBytes, false, EventSeverity.INFO, t0, now);
            return staleOk ? new Lookup<>(false, null, true, staleValue, staleUntil) : Lookup.miss();
        }
        lru.touch(e);
        lfu.touch(e);
        hits++;
        l1Hits++;
        sourceCallsAvoided++;
        window.add(RollingWindow.HITS, now);
        if (routineSampled()) {
            emit(CacheAction.HIT, e.keyFingerprint, "Valid entry returned (frequency now " + e.frequency + ")", e,
                    map.size(), memoryBytes, true, EventSeverity.INFO, t0, now);
        }
        e.lastAccessTime = now;
        return Lookup.hit(e.value);
    }

    /** Evicts one L1 entry chosen by the active policy. @return false if nothing could be evicted. */
    private boolean evictOne(EvictCause cause, long now, long t0) {
        CacheEntry<V> v = policy == EvictionPolicy.LRU ? lru.first() : lfu.victim();
        if (v == null) return false;
        int sizeBefore = map.size();
        long memBefore = memoryBytes;
        EvictionPolicy used = policy;
        boolean toVictim = victimEnabled && v.expiresAt > now && v.estimatedSizeBytes <= victimMaxBytes();
        detachL1(v);
        evictions++;
        if (used == EvictionPolicy.LRU) lruEvictions++; else lfuEvictions++;
        if (cause == EvictCause.ENTRY_LIMIT) entryLimitEvictions++; else memoryLimitEvictions++;
        window.add(RollingWindow.EVICTIONS, now);
        String limit = cause == EvictCause.ENTRY_LIMIT
                ? "Entry limit reached (" + maximumEntries + " entries)"
                : "Estimated memory limit reached (" + Humanize.bytes(maximumMemoryBytes) + ")";
        String who = used == EvictionPolicy.LRU
                ? "least-recently-used entry had not been accessed for " + Humanize.millis(Math.max(0, now - v.lastAccessTime))
                : "least-frequently-used entry (frequency " + v.frequency + "; ties broken by least recent access) selected";
        String reason = limit + "; " + who + ". Released " + Humanize.bytes(v.estimatedSizeBytes) + "."
                + (toVictim ? " Moved to victim cache with its original expiry." : "");
        emit(cause == EvictCause.ENTRY_LIMIT ? CacheAction.ENTRY_LIMIT_EVICTED : CacheAction.MEMORY_EVICTED,
                v.keyFingerprint, reason, v, sizeBefore, memBefore, false,
                cause == EvictCause.MEMORY_LIMIT || v.frequency >= 3 ? EventSeverity.WARN : EventSeverity.INFO, t0, now);
        if (toVictim) moveToVictim(v, now, t0); else v.live = false;
        return true;
    }

    @SuppressWarnings("unchecked")
    private void moveToVictim(CacheEntry<V> v, long now, long t0) {
        v.inVictim = true;
        victim.put((K) v.key, v);
        victimBytes += v.estimatedSizeBytes;
        trimVictim(now, t0);
    }

    private void trimVictim(long now, long t0) {
        if (!victimEnabled) return;
        int cap = victimCapacity();
        long maxBytes = victimMaxBytes();
        while (victim.size() > cap || victimBytes > maxBytes) {
            var it = victim.entrySet().iterator();
            if (!it.hasNext()) break;
            CacheEntry<V> old = it.next().getValue();
            it.remove();
            victimBytes -= old.estimatedSizeBytes;
            old.inVictim = false;
            old.live = false;
            victimEvictions++;
            emit(CacheAction.EVICTED, old.keyFingerprint, "Victim cache full (" + cap + " entries); oldest demoted entry dropped. Released "
                    + Humanize.bytes(old.estimatedSizeBytes) + ".", old, map.size(), memoryBytes, false, EventSeverity.INFO, t0, now);
        }
    }

    private int victimCapacity() {
        return Math.max(1, (int) (maximumEntries * config.victimCacheFraction()));
    }

    private long victimMaxBytes() {
        return Math.max(1L, (long) (maximumMemoryBytes * config.victimCacheFraction()));
    }

    /** Removes expired entries (both layers). @return number removed. */
    private int purgeExpired(long now, long t0) {
        int sizeBefore = map.size();
        long memBefore = memoryBytes;
        int removed = 0;
        int logged = 0;
        long freed = 0;
        CacheEntry<V> e;
        while ((e = expiry.<V>pollExpired(now)) != null) {
            if (!e.live || e.expiresAt > now) continue;
            boolean log = logged < MAX_EXPIRED_EVENTS_PER_PURGE;
            freed += e.estimatedSizeBytes;
            expireEntry(e, now, t0, "TTL elapsed " + Humanize.millis(now - e.expiresAt) + " ago; removed by expired-entry cleanup", log);
            if (log) logged++;
            removed++;
        }
        if (removed > 1 || removed > logged) {
            emit(CacheAction.CLEANUP, null, "Removed " + removed + " expired entries (" + logged + " logged individually); released "
                    + Humanize.bytes(freed) + ".", null, sizeBefore, memBefore, false, EventSeverity.INFO, t0, now);
        }
        return removed;
    }

    private void expireEntry(CacheEntry<V> e, long now, long t0, String how, boolean log) {
        int sizeBefore = map.size();
        long memBefore = memoryBytes;
        removeAnyLayer(e);
        expirations++;
        window.add(RollingWindow.EXPIRATIONS, now);
        if (log) {
            emit(CacheAction.EXPIRED, e.keyFingerprint, how, e, sizeBefore, memBefore, false, EventSeverity.INFO, t0, now);
        }
    }

    private void attachL1(CacheEntry<V> e) {
        @SuppressWarnings("unchecked")
        K k = (K) e.key;
        map.put(k, e);
        lru.addLast(e);
        lfu.add(e);
        memoryBytes += e.estimatedSizeBytes;
        e.inVictim = false;
        e.live = true;
    }

    private void detachL1(CacheEntry<V> e) {
        map.remove(e.key);
        lru.remove(e);
        lfu.remove(e);
        memoryBytes -= e.estimatedSizeBytes;
    }

    private void dropVictimEntry(CacheEntry<V> v) {
        dropVictimEntryKeepLive(v);
        v.live = false;
    }

    private void dropVictimEntryKeepLive(CacheEntry<V> v) {
        victim.remove(v.key);
        victimBytes -= v.estimatedSizeBytes;
        v.inVictim = false;
    }

    private void removeAnyLayer(CacheEntry<V> e) {
        if (e.inVictim) dropVictimEntry(e); else { detachL1(e); e.live = false; }
    }

    private boolean routineSampled() {
        return (routineCounter++ % config.routineEventSampling()) == 0;
    }

    private void recordShadow(K key, long now) {
        if (shadow == null) return;
        int cap = maximumEntries;
        if (!map.isEmpty()) {
            long avg = Math.max(1, memoryBytes / map.size());
            cap = (int) Math.max(1, Math.min(cap, maximumMemoryBytes / avg));
        }
        long h = (long) key.hashCode() * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        shadow.record(h, now, cap, Math.max(1, defaultTtl.toMillis()));
    }

    private void emit(CacheAction action, String fingerprint, String reason, CacheEntry<V> entry, int sizeBefore,
                      long memBefore, boolean returned, EventSeverity severity, long t0, long now) {
        long freq = entry == null ? 0 : entry.frequency;
        long age = entry == null ? 0 : Math.max(0, now - entry.lastAccessTime);
        long ttlLeft = entry == null ? 0 : Math.max(0, entry.expiresAt - now);
        long size = entry == null ? 0 : entry.estimatedSizeBytes;
        double latency = t0 == 0 ? 0 : Humanize.round4((System.nanoTime() - t0) / 1_000_000.0);
        CacheDecisionEvent ev = new CacheDecisionEvent(++eventSeq, Instant.ofEpochMilli(now), app, env, region, fingerprint,
                action, reason, policy, freq, age, ttlLeft, size, sizeBefore, map.size(), memBefore, memoryBytes, returned,
                severity, latency);
        if (events.size() >= config.decisionEventCapacity()) events.pollFirst();
        events.addLast(ev);
        telemetry.publishEvent(TelemetryEvent.from(ev));
    }

    private CacheMetrics metricsLocked() {
        double avgGet = getOps == 0 ? 0 : Humanize.round4(getNanos / (double) getOps / 1_000_000.0);
        double avgPut = putOps == 0 ? 0 : Humanize.round4(putNanos / (double) putOps / 1_000_000.0);
        return new CacheMetrics(hits, misses, puts, removes, clears, evictions, expirations, map.size(), maximumEntries,
                memoryBytes, maximumMemoryBytes, policy, lruEvictions, lfuEvictions, entryLimitEvictions,
                memoryLimitEvictions, avgGet, avgPut, sourceCallsAvoided, telemetry.eventsSent(region),
                telemetry.eventsFailed(region), telemetry.failureStreak(), l1Hits, victimHits, sourceMisses, victimEvictions,
                victimEnabled, victim.size(), victimEnabled ? victimCapacity() : 0, refreshesStarted, coalesced,
                stampedeAvoided, refreshFailures, sourceCalls, sourceErrors, staleServed, staleCorrections, staleViolations);
    }

    private CacheHealthReport healthLocked() {
        long now = clock.millis();
        CacheMetrics m = metricsLocked();
        double srcErr = m.sourceCalls() == 0 ? 0 : (double) m.sourceErrors() / m.sourceCalls() * 100.0;
        return CacheHealthEvaluator.evaluate(new CacheHealthEvaluator.Input(m.requests(), m.hitRate(),
                m.memoryUtilizationPercent(), window.sum(RollingWindow.EVICTIONS, now), window.sum(RollingWindow.EXPIRATIONS, now),
                window.sum(RollingWindow.PUTS, now), RollingWindow.MINUTES, m.telemetryFailureStreak(), config.riskLevel(),
                m.staleDataViolations(), srcErr, null));
    }

    // ---- single-flight loading (not under lock) -------------------------------------------------------------------

    private V loadSingleFlight(K key, Duration ttl, Function<? super K, ? extends V> loader, Lookup<V> l, boolean forceFresh) {
        if (!config.stampedeShieldEnabled()) return loadAndStore(key, ttl, loader, null);
        boolean serveStale = l.hasStale() && swrAllowed && !forceFresh;
        Refresh<V> mine = new Refresh<>(serveStale, l.stale(), l.staleUntil());
        Refresh<V> existing = inflight.putIfAbsent(key, mine);
        if (existing != null) return follow(key, existing);

        // Leader. Re-check: another leader may have finished between our miss and putIfAbsent.
        if (!forceFresh) {
            Optional<V> again = peekValid(key);
            if (again.isPresent()) {
                mine.future.complete(again.get());
                inflight.remove(key, mine);
                lock.lock();
                try {
                    stampedeAvoided++;
                    sourceCallsAvoided++;
                } finally {
                    lock.unlock();
                }
                return again.get();
            }
        }
        lock.lock();
        try {
            refreshesStarted++;
            emit(CacheAction.REFRESH_STARTED, sanitizer.fingerprint(region, key), "Single-flight refresh started; concurrent requests for this key share one source call"
                    + (serveStale ? " (serving the just-expired value while revalidating)" : ""), null, map.size(), memoryBytes,
                    false, EventSeverity.INFO, System.nanoTime(), clock.millis());
            if (serveStale) {
                staleServed++;
                sourceCallsAvoided++;
            }
        } finally {
            lock.unlock();
        }
        if (serveStale) {
            RefreshPool.POOL.execute(() -> {
                try {
                    loadAndStore(key, ttl, loader, mine);
                } catch (RuntimeException ignored) {
                    // failure already recorded; callers keep getting source validation once the stale window closes
                }
            });
            return l.stale();
        }
        return loadAndStore(key, ttl, loader, mine);
    }

    private V follow(K key, Refresh<V> existing) {
        lock.lock();
        try {
            coalesced++;
            stampedeAvoided++;
            sourceCallsAvoided++;
            long now = clock.millis();
            if (existing.hasStale && now <= existing.staleUntil) {
                staleServed++;
                emit(CacheAction.STALE_SERVED, sanitizer.fingerprint(region, key),
                        "Served stale value (LOW-risk region, stale-while-revalidate) while one refresh is in flight", null,
                        map.size(), memoryBytes, true, EventSeverity.INFO, 0, now);
                return existing.stale;
            }
            if (routineSampled()) {
                emit(CacheAction.REFRESH_COALESCED, sanitizer.fingerprint(region, key),
                        "Request coalesced onto the in-flight refresh; no additional source call made", null, map.size(),
                        memoryBytes, false, EventSeverity.INFO, 0, now);
            }
        } finally {
            lock.unlock();
        }
        try {
            return existing.future.get(config.loadWaitTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException ee) {
            throw new CacheLoadException("Shared source load failed", ee.getCause());
        } catch (TimeoutException te) {
            throw new CacheLoadException("Timed out waiting for the shared source load");
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CacheLoadException("Interrupted while waiting for the shared source load");
        }
    }

    private V loadAndStore(K key, Duration ttl, Function<? super K, ? extends V> loader, Refresh<V> mine) {
        lock.lock();
        try {
            sourceCalls++;
        } finally {
            lock.unlock();
        }
        try {
            V v = loader.apply(key);
            if (v == null) throw new CacheLoadException("Source loader returned null");
            put(key, v, ttl);
            if (mine != null) mine.future.complete(v);
            return v;
        } catch (Throwable t) {
            lock.lock();
            try {
                sourceErrors++;
                refreshFailures++;
                // Only the exception class is reported: messages from sources can contain sensitive data.
                emit(CacheAction.REFRESH_FAILED, sanitizer.fingerprint(region, key), "Source load failed ("
                        + t.getClass().getSimpleName() + "); nothing was cached", null, map.size(), memoryBytes, false,
                        EventSeverity.CRITICAL, 0, clock.millis());
            } finally {
                lock.unlock();
            }
            if (mine != null) mine.future.completeExceptionally(t);
            if (t instanceof CacheLoadException cle) throw cle;
            if (t instanceof Error err) throw err;
            throw new CacheLoadException("Source load failed", t);
        } finally {
            if (mine != null) inflight.remove(key, mine);
        }
    }

    @Override
    public String toString() {
        return "AcentraCache[region=" + region + ", application=" + app + "]";
    }
}

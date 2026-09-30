package com.acentra.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AcentraCacheTest {

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
    }

    private CacheRegionConfig.Builder base(int maxEntries, EvictionPolicy policy) {
        return CacheRegionConfig.builder()
                .regionName("test-region")
                .applicationName("test-app")
                .environment("test")
                .maximumEntries(maxEntries)
                .maximumMemoryBytes(64L * 1024 * 1024)
                .defaultPolicy(policy)
                .defaultTtl(Duration.ofMinutes(10))
                .routineEventSampling(1)
                .clock(clock);
    }

    private AcentraCache<String, String> cache(int maxEntries, EvictionPolicy policy) {
        return new AcentraCache<>(base(maxEntries, policy).build());
    }

    private static List<CacheAction> actions(AcentraCache<?, ?> c) {
        return c.getDecisionEvents().stream().map(CacheDecisionEvent::action).toList();
    }

    @Test
    void putThenGetProducesHit() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("a", "1", Duration.ofMinutes(1));
        assertThat(c.get("a")).contains("1");
        CacheMetrics m = c.getMetrics();
        assertThat(m.hits()).isEqualTo(1);
        assertThat(m.misses()).isZero();
        assertThat(m.puts()).isEqualTo(1);
        assertThat(actions(c)).contains(CacheAction.PUT, CacheAction.HIT);
    }

    @Test
    void getMissingKeyProducesMiss() {
        var c = cache(10, EvictionPolicy.LRU);
        assertThat(c.get("nope")).isEmpty();
        assertThat(c.getMetrics().misses()).isEqualTo(1);
        assertThat(actions(c)).containsExactly(CacheAction.MISS);
    }

    @Test
    void expiredEntryProducesMissAndExpirationAndIsNeverReturned() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("a", "1", Duration.ofMillis(500));
        assertThat(c.get("a")).isPresent();
        clock.advance(Duration.ofMillis(600));
        assertThat(c.get("a")).isEmpty();
        CacheMetrics m = c.getMetrics();
        assertThat(m.expirations()).isEqualTo(1);
        assertThat(m.misses()).isEqualTo(1);
        assertThat(m.hits()).isEqualTo(1);
        assertThat(m.size()).isZero();
        assertThat(actions(c)).contains(CacheAction.EXPIRED, CacheAction.MISS);
    }

    @Test
    void ttlIsCheckedBeforeEvictionMetadata_expiredButMostFrequentIsNotAHit() {
        var c = cache(10, EvictionPolicy.LFU);
        c.put("hot", "v", Duration.ofSeconds(1));
        for (int i = 0; i < 50; i++) assertThat(c.get("hot")).isPresent();
        clock.advance(Duration.ofSeconds(2));
        assertThat(c.get("hot")).as("very popular but expired").isEmpty();
        assertThat(c.getMetrics().expirations()).isEqualTo(1);
    }

    @Test
    void perEntryTtlIsIndependent() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("short", "s", Duration.ofSeconds(30));
        c.put("long", "l", Duration.ofHours(24));
        clock.advance(Duration.ofMinutes(1));
        assertThat(c.get("short")).isEmpty();
        assertThat(c.get("long")).contains("l");
        assertThat(c.peekEntry("long").orElseThrow().remainingTtlMillis(clock.millis()))
                .isEqualTo(Duration.ofHours(24).minusMinutes(1).toMillis());
    }

    @Test
    void lruEvictsLeastRecentlyUsed_capacityPatternD() {
        var c = cache(3, EvictionPolicy.LRU);
        c.put("A", "a");
        c.put("B", "b");
        c.put("C", "c");
        c.get("A");
        c.put("D", "d");
        assertThat(c.peekEntry("B")).isEmpty();
        assertThat(c.peekEntry("A")).isPresent();
        assertThat(c.peekEntry("C")).isPresent();
        assertThat(c.peekEntry("D")).isPresent();
        CacheMetrics m = c.getMetrics();
        assertThat(m.evictions()).isEqualTo(1);
        assertThat(m.lruEvictions()).isEqualTo(1);
        assertThat(m.evictionsDueToEntryLimit()).isEqualTo(1);
        assertThat(actions(c)).contains(CacheAction.ENTRY_LIMIT_EVICTED);
    }

    @Test
    void lruProtectsMostRecentlyAccessedEntry() {
        var c = cache(2, EvictionPolicy.LRU);
        c.put("A", "a");
        c.put("B", "b");
        c.get("A");
        c.put("C", "c");
        assertThat(c.peekEntry("A")).isPresent();
        assertThat(c.peekEntry("B")).isEmpty();
    }

    @Test
    void lfuEvictsLowestFrequency() {
        var c = cache(3, EvictionPolicy.LFU);
        c.put("A", "a");
        c.put("B", "b");
        c.put("C", "c");
        c.get("A");
        c.get("A");
        c.get("B");
        c.put("D", "d");
        assertThat(c.peekEntry("C")).as("C has the lowest frequency").isEmpty();
        assertThat(c.peekEntry("A")).isPresent();
        assertThat(c.peekEntry("B")).isPresent();
        assertThat(c.getMetrics().lfuEvictions()).isEqualTo(1);
    }

    @Test
    void lfuTieBreaksByLeastRecentlyUsed() {
        var c = cache(3, EvictionPolicy.LFU);
        c.put("A", "a");
        c.put("B", "b");
        c.put("C", "c");
        c.get("A"); // A:1
        c.get("B"); // B:1, C:0 -> C lowest
        c.get("C"); // C:1 -> all freq 1; recency order A (oldest), B, C
        c.put("D", "d");
        assertThat(c.peekEntry("A")).as("A is the least recently used among equal frequencies").isEmpty();
        assertThat(c.peekEntry("B")).isPresent();
        assertThat(c.peekEntry("C")).isPresent();
    }

    @Test
    void frequencyIncreasesOnlyOnValidHits() {
        var c = cache(3, EvictionPolicy.LFU);
        c.put("A", "a", Duration.ofSeconds(10));
        c.get("A");
        c.get("A");
        assertThat(c.peekEntry("A").orElseThrow().getFrequency()).isEqualTo(2);
        c.get("missing");
        assertThat(c.peekEntry("A").orElseThrow().getFrequency()).isEqualTo(2);
        c.peekEntry("A");
        assertThat(c.peekEntry("A").orElseThrow().getFrequency()).as("peek does not count").isEqualTo(2);
    }

    @Test
    void overwriteDoesNotIncreaseSizeOrEvict() {
        var c = cache(2, EvictionPolicy.LRU);
        c.put("A", "a1");
        c.put("B", "b");
        c.put("A", "a2");
        c.put("A", "a3");
        assertThat(c.size()).isEqualTo(2);
        assertThat(c.getMetrics().evictions()).isZero();
        assertThat(c.get("A")).contains("a3");
        assertThat(c.get("B")).contains("b");
    }

    @Test
    void entryLimitTriggersEvictionAndKeepsSizeBounded() {
        var c = cache(5, EvictionPolicy.LRU);
        for (int i = 0; i < 20; i++) c.put("k" + i, "v" + i);
        assertThat(c.size()).isEqualTo(5);
        assertThat(c.getMetrics().evictionsDueToEntryLimit()).isEqualTo(15);
    }

    @Test
    void memoryLimitTriggersEvictionPatternE() {
        var c = new AcentraCache<String, String>(base(10_000, EvictionPolicy.LRU).maximumMemoryBytes(8 * 1024).build());
        String big = "x".repeat(1500);
        for (int i = 0; i < 12; i++) c.put("big-" + i, big);
        CacheMetrics m = c.getMetrics();
        assertThat(m.evictionsDueToMemoryLimit()).isGreaterThan(0);
        assertThat(m.estimatedMemoryUsageBytes()).isLessThanOrEqualTo(8 * 1024);
        assertThat(m.memoryUtilizationPercent()).isLessThanOrEqualTo(100.0);
        assertThat(actions(c)).contains(CacheAction.MEMORY_EVICTED);
        assertThat(c.size()).isLessThan(12);
    }

    @Test
    void expiredEntriesAreCleanedBeforeEvictingLiveOnes() {
        var c = cache(3, EvictionPolicy.LRU);
        c.put("old1", "x", Duration.ofSeconds(5));
        c.put("old2", "x", Duration.ofSeconds(5));
        c.put("live", "x", Duration.ofHours(1));
        clock.advance(Duration.ofSeconds(10));
        c.put("new", "x");
        assertThat(c.getMetrics().evictions()).as("expired entries made room; nothing live was evicted").isZero();
        assertThat(c.getMetrics().expirations()).isEqualTo(2);
        assertThat(c.peekEntry("live")).isPresent();
        assertThat(actions(c)).contains(CacheAction.CLEANUP);
    }

    @Test
    void cleanUpRemovesExpiredEntries() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("a", "1", Duration.ofSeconds(1));
        c.put("b", "2", Duration.ofSeconds(1));
        c.put("c", "3", Duration.ofHours(1));
        clock.advance(Duration.ofSeconds(2));
        assertThat(c.cleanUp()).isEqualTo(2);
        assertThat(c.size()).isEqualTo(1);
    }

    @Test
    void oversizedEntryIsRejectedAndOldValueRemoved() {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).maximumMemoryBytes(1024).build());
        c.put("k", "small");
        c.put("k", "y".repeat(5000));
        assertThat(c.get("k")).isEmpty();
        assertThat(c.size()).isZero();
    }

    @Test
    void removeAndClearUpdateMetricsAndEvents() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("a", "1");
        c.put("b", "2");
        assertThat(c.remove("a")).isTrue();
        assertThat(c.remove("a")).isFalse();
        c.clear();
        CacheMetrics m = c.getMetrics();
        assertThat(m.removes()).isEqualTo(1);
        assertThat(m.clears()).isEqualTo(1);
        assertThat(c.size()).isZero();
        assertThat(c.estimatedMemoryUsageBytes()).isZero();
        assertThat(actions(c)).contains(CacheAction.REMOVE, CacheAction.CLEAR);
    }

    @Test
    void metricsFormulasHoldAndRatesSumToOneHundred() {
        var c = cache(10, EvictionPolicy.LRU);
        assertThat(c.getMetrics().hitRate()).isZero();
        assertThat(c.getMetrics().missRate()).isZero();
        c.put("a", "1");
        for (int i = 0; i < 7; i++) c.get("a");
        for (int i = 0; i < 3; i++) c.get("zz" + i);
        CacheMetrics m = c.getMetrics();
        assertThat(m.hitRate()).isEqualTo(70.0);
        assertThat(m.missRate()).isEqualTo(30.0);
        assertThat(m.hitRate() + m.missRate()).isEqualTo(100.0);
        assertThat(m.sourceCallsAvoided()).isEqualTo(7);
    }

    @Test
    void thirdsStillSumToOneHundred() {
        var c = cache(10, EvictionPolicy.LRU);
        c.put("a", "1");
        c.get("a");
        c.get("x");
        c.get("y");
        CacheMetrics m = c.getMetrics();
        assertThat(m.hitRate() + m.missRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void policyCanChangeAtRuntimeAndChangesVictimSelection() {
        var c = cache(3, EvictionPolicy.LRU);
        c.put("A", "a");
        c.put("B", "b");
        c.put("C", "c");
        c.get("A");
        c.get("A");
        c.get("A");
        c.get("B");
        c.get("C"); // recency order: A, B, C (A least recent) but A is by far the most frequent
        c.changePolicy(EvictionPolicy.LFU);
        assertThat(c.getCurrentPolicy()).isEqualTo(EvictionPolicy.LFU);
        c.put("D", "d");
        assertThat(c.peekEntry("A")).as("LFU keeps the frequent entry that LRU would have evicted").isPresent();
        assertThat(c.peekEntry("B")).as("B and C tie at frequency 1; B is least recent").isEmpty();
        assertThat(actions(c)).contains(CacheAction.POLICY_CHANGED);
    }

    @Test
    void reconfigureShrinkEvictsAccordingToPolicy() {
        var c = cache(5, EvictionPolicy.LRU);
        for (int i = 0; i < 5; i++) c.put("k" + i, "v");
        c.reconfigure(2, null, null, "test");
        assertThat(c.size()).isEqualTo(2);
        assertThat(c.getConfig().maximumEntries()).isEqualTo(2);
        assertThat(c.peekEntry("k4")).isPresent();
        assertThat(c.peekEntry("k3")).isPresent();
        assertThat(actions(c)).contains(CacheAction.CONFIG_CHANGED);
    }

    @Test
    void decisionEventsAreBoundedAndContainNoRawKeyOrValue() {
        var c = new AcentraCache<String, String>(base(3, EvictionPolicy.LRU).decisionEventCapacity(25).build());
        String secretKey = "eligibility:member:987654321";
        String secretValue = "DIAGNOSIS-E11.9-patient-jane-doe";
        c.put(secretKey, secretValue);
        c.get(secretKey);
        c.get("eligibility:member:111111111");
        for (int i = 0; i < 100; i++) c.put("filler-" + i, "f");
        assertThat(c.getDecisionEvents()).hasSize(25);
        for (CacheDecisionEvent e : c.getDecisionEvents()) {
            String dump = e.toString();
            assertThat(dump).doesNotContain("987654321").doesNotContain("jane-doe").doesNotContain("DIAGNOSIS");
            if (e.keyFingerprint() != null) assertThat(e.keyFingerprint()).startsWith("sha256:");
        }
    }

    @Test
    void routineEventsAreSampledButImportantEventsAreNot() {
        var c = new AcentraCache<String, String>(base(2, EvictionPolicy.LRU).routineEventSampling(10).build());
        c.put("a", "1");
        for (int i = 0; i < 50; i++) c.get("a");
        long hitEvents = c.getDecisionEvents().stream().filter(e -> e.action() == CacheAction.HIT).count();
        assertThat(hitEvents).isBetween(4L, 6L);
        assertThat(c.getMetrics().hits()).isEqualTo(50);
        c.get("missing");
        assertThat(actions(c)).contains(CacheAction.MISS);
    }

    @Test
    void eventCarriesXRayFields() {
        var c = cache(1, EvictionPolicy.LRU);
        c.put("A", "value-a", Duration.ofSeconds(45));
        clock.advance(Duration.ofSeconds(74));
        c.put("A2", "x", Duration.ofMinutes(10));
        CacheDecisionEvent ev = c.getDecisionEvents().stream()
                .filter(e -> e.action() == CacheAction.ENTRY_LIMIT_EVICTED || e.action() == CacheAction.EXPIRED).findFirst().orElseThrow();
        assertThat(ev.applicationName()).isEqualTo("test-app");
        assertThat(ev.environment()).isEqualTo("test");
        assertThat(ev.cacheRegion()).isEqualTo("test-region");
        assertThat(ev.keyFingerprint()).startsWith("sha256:");
        assertThat(ev.reason()).isNotBlank();
        assertThat(ev.cacheSizeBefore()).isGreaterThanOrEqualTo(ev.cacheSizeAfter());
    }

    @Test
    void lruEvictionReasonExplainsInactivity() {
        var c = cache(1, EvictionPolicy.LRU);
        c.put("A", "a", Duration.ofMinutes(10));
        clock.advance(Duration.ofSeconds(74));
        c.put("B", "b", Duration.ofMinutes(10));
        CacheDecisionEvent ev = c.getDecisionEvents().stream()
                .filter(e -> e.action() == CacheAction.ENTRY_LIMIT_EVICTED).findFirst().orElseThrow();
        assertThat(ev.reason()).contains("Entry limit reached").contains("74 seconds").contains("Released");
        assertThat(ev.lastAccessAgeMs()).isEqualTo(74_000);
        assertThat(ev.remainingTtlMs()).isEqualTo(Duration.ofMinutes(10).minusSeconds(74).toMillis());
        assertThat(ev.activePolicy()).isEqualTo(EvictionPolicy.LRU);
    }

    // ---- victim cache -----------------------------------------------------------------------------------------------

    @Test
    void victimCachePromotesEvictedEntryAndKeepsOriginalExpiry() {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).victimCacheEnabled(true).victimCacheFraction(0.5).build());
        c.put("A", "a", Duration.ofMinutes(10));
        for (int i = 0; i < 10; i++) c.put("f" + i, "x", Duration.ofMinutes(10));
        assertThat(c.peekEntry("A")).as("A was evicted from L1 into the victim layer").isPresent();
        long expiresAt = c.peekEntry("A").orElseThrow().getExpiresAt();
        clock.advance(Duration.ofMinutes(3));
        assertThat(c.get("A")).contains("a");
        CacheMetrics m = c.getMetrics();
        assertThat(m.victimHits()).isEqualTo(1);
        assertThat(m.l1Hits()).isZero();
        assertThat(m.hits()).isEqualTo(1);
        assertThat(c.peekEntry("A").orElseThrow().getExpiresAt()).as("expiry is not extended by the move").isEqualTo(expiresAt);
        assertThat(actions(c)).contains(CacheAction.VICTIM_HIT);
        assertThat(m.overallHitRate()).isEqualTo(100.0);
    }

    @Test
    void victimEntryThatExpiredIsNotReturned() {
        var c = new AcentraCache<String, String>(base(2, EvictionPolicy.LRU).victimCacheEnabled(true).victimCacheFraction(0.5).build());
        c.put("A", "a", Duration.ofSeconds(30));
        c.put("B", "b", Duration.ofMinutes(10));
        c.put("C", "c", Duration.ofMinutes(10));
        clock.advance(Duration.ofSeconds(31));
        assertThat(c.get("A")).isEmpty();
        assertThat(c.getMetrics().victimHits()).isZero();
        assertThat(c.getMetrics().sourceMisses()).isEqualTo(1);
    }

    @Test
    void victimLayerIsBounded() {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).victimCacheEnabled(true).victimCacheFraction(0.2).build());
        for (int i = 0; i < 40; i++) c.put("k" + i, "v");
        CacheMetrics m = c.getMetrics();
        assertThat(m.victimSize()).isLessThanOrEqualTo(2);
        assertThat(m.victimEvictions()).isGreaterThan(0);
    }

    // ---- stampede shield / SWR / validation -----------------------------------------------------------------------

    @Test
    void getOrLoadCachesTheLoadedValue() {
        var c = cache(10, EvictionPolicy.LRU);
        int[] calls = {0};
        assertThat(c.getOrLoad("k", k -> { calls[0]++; return "v"; })).isEqualTo("v");
        assertThat(c.getOrLoad("k", k -> { calls[0]++; return "v2"; })).isEqualTo("v");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(c.getMetrics().sourceCalls()).isEqualTo(1);
    }

    @Test
    void getOrLoadFailureIsReportedAndNotCached() {
        var c = cache(10, EvictionPolicy.LRU);
        assertThatThrownBy(() -> c.getOrLoad("k", k -> { throw new IllegalStateException("db down: member 12345"); }))
                .isInstanceOf(CacheLoadException.class);
        assertThat(c.size()).isZero();
        CacheMetrics m = c.getMetrics();
        assertThat(m.refreshFailures()).isEqualTo(1);
        assertThat(m.sourceErrors()).isEqualTo(1);
        CacheDecisionEvent failed = c.getDecisionEvents().stream().filter(e -> e.action() == CacheAction.REFRESH_FAILED).findFirst().orElseThrow();
        assertThat(failed.reason()).doesNotContain("12345").doesNotContain("db down");
    }

    @Test
    void staleWhileRevalidateOnlyAllowedForLowRisk() {
        assertThatThrownBy(() -> base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.HIGH).staleWhileRevalidate(true).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("LOW");
        assertThatThrownBy(() -> base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.CRITICAL).staleWhileRevalidate(true).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.LOW).staleWhileRevalidate(true).build()).isNotNull();
    }

    @Test
    void highRiskRegionNeverServesStaleValue() throws Exception {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.HIGH).build());
        c.put("k", "old", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(2));
        String v = c.getOrLoad("k", k -> "fresh");
        assertThat(v).isEqualTo("fresh");
        assertThat(c.getMetrics().staleServed()).isZero();
    }

    @Test
    void lowRiskSwrServesStaleOnceWhileOneRefreshRuns() throws Exception {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.LOW)
                .staleWhileRevalidate(true).staleGrace(Duration.ofSeconds(60)).build());
        c.put("k", "old", Duration.ofSeconds(1));
        clock.advance(Duration.ofSeconds(5));
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        String v = c.getOrLoad("k", k -> {
            try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            return "new";
        });
        assertThat(v).as("stale value returned immediately").isEqualTo("old");
        assertThat(c.getOrLoad("k", k -> "never-called")).as("follower during refresh gets stale too").isEqualTo("old");
        release.countDown();
        long deadline = System.currentTimeMillis() + 5000;
        while (c.get("k").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10);
        assertThat(c.get("k")).contains("new");
        assertThat(c.getMetrics().staleServed()).isEqualTo(2);
        assertThat(c.getMetrics().refreshesStarted()).isEqualTo(1);
    }

    @Test
    void requireFreshFromSourceAlwaysCallsSourceAndDetectsDrift() {
        var c = new AcentraCache<String, String>(base(10, EvictionPolicy.LRU).riskLevel(CacheRiskLevel.CRITICAL).build());
        c.put("auth", "PENDING", Duration.ofMinutes(1));
        int[] calls = {0};
        String fresh = c.requireFreshFromSource("auth", null, k -> { calls[0]++; return "APPROVED"; });
        assertThat(fresh).isEqualTo("APPROVED");
        assertThat(calls[0]).isEqualTo(1);
        assertThat(c.getMetrics().staleCorrections()).isEqualTo(1);
        c.requireFreshFromSource("auth", null, k -> { calls[0]++; return "APPROVED"; });
        assertThat(calls[0]).as("cached value is never trusted for the final action").isEqualTo(2);
        assertThat(c.getMetrics().staleCorrections()).isEqualTo(1);
        assertThat(c.get("auth")).contains("APPROVED");
    }

    // ---- snapshot / health / recommendation ---------------------------------------------------------------------

    @Test
    void snapshotCarriesCountersAndShadowData() {
        var c = cache(3, EvictionPolicy.LRU);
        for (int i = 0; i < 150; i++) {
            String k = "k" + (i % 5);
            if (c.get(k).isEmpty()) c.put(k, "v");
        }
        var s = c.snapshot();
        assertThat(s.cacheRegion()).isEqualTo("test-region");
        assertThat(s.hits() + s.misses()).isEqualTo(150);
        assertThat(s.capacity()).isEqualTo(3);
        assertThat(s.shadow()).isNotNull();
        assertThat(s.shadow().windowRequests()).isEqualTo(150);
        assertThat(s.recentWindow().minutes()).isEqualTo(10);
        assertThat(s.recentWindow().hits() + s.recentWindow().misses()).isEqualTo(150);
    }

    @Test
    void healthIsUnknownWithFewRequestsAndExplainsReasons() {
        var c = cache(10, EvictionPolicy.LRU);
        assertThat(c.getHealth().status()).isEqualTo(CacheHealthStatus.UNKNOWN);
        c.put("a", "1");
        for (int i = 0; i < 100; i++) c.get("a");
        CacheHealthReport h = c.getHealth();
        assertThat(h.status()).isEqualTo(CacheHealthStatus.EXCELLENT);
        assertThat(h.reasons()).isNotEmpty();
    }

    @Test
    void recommendationRequiresMinimumSample() {
        var c = cache(3, EvictionPolicy.LRU);
        c.get("a");
        CachePolicyRecommendation r = c.getRecommendation();
        assertThat(r.minimumSampleMet()).isFalse();
        assertThat(r.action()).isEqualTo(CachePolicyRecommendation.Action.KEEP);
        assertThat(r.approvalRequired()).isTrue();
    }

    @Test
    void peekReturnsAnImmutableCopyAndNeverExposesRawKey() {
        var c = cache(3, EvictionPolicy.LRU);
        c.put("secret-key", "v", Duration.ofMinutes(1), "v42");
        Optional<CacheEntry<String>> e = c.peekEntry("secret-key");
        assertThat(e).isPresent();
        assertThat(e.get().getSourceVersion()).isEqualTo("v42");
        assertThat(e.get().getCacheRegion()).isEqualTo("test-region");
        assertThat(e.get().getRiskLevel()).isEqualTo(CacheRiskLevel.LOW);
        assertThat(e.get().getEstimatedSizeBytes()).isPositive();
        assertThat(e.get().getKeyFingerprint()).startsWith("sha256:").doesNotContain("secret-key");
        assertThat(c.toString()).doesNotContain("secret-key");
    }

    @Test
    void invalidConfigIsRejected() {
        assertThatThrownBy(() -> CacheRegionConfig.builder().regionName("Bad Name").build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(0, EvictionPolicy.LRU).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base(1, EvictionPolicy.LRU).defaultTtl(Duration.ZERO).build()).isInstanceOf(IllegalArgumentException.class);
        var c = cache(1, EvictionPolicy.LRU);
        assertThatThrownBy(() -> c.put("a", "b", Duration.ofMillis(-1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> c.put(null, "b")).isInstanceOf(NullPointerException.class);
    }
}

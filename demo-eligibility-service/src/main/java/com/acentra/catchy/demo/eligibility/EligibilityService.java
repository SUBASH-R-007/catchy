package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheEntry;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResult;
import com.acentra.catchy.demo.eligibility.EligibilityModels.ServedFrom;
import com.acentra.catchy.demo.eligibility.EligibilityModels.SourceChangeResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.ValidationResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Eligibility lookups. HIGH-risk region: only a minimal derived result is cached, under a salted-hash key (never the raw
 * synthetic member id), for at most 2 minutes, never served stale. {@link #validate} is the source-validation example.
 */
@Service
public class EligibilityService {

    private final AcentraCache<String, EligibilityResult> cache;
    private final MemberKeyHasher hasher;
    private final SimulatedSource source;

    public EligibilityService(AcentraCache<String, EligibilityResult> eligibilitySummaryCache, MemberKeyHasher hasher,
                              SimulatedSource source) {
        this.cache = eligibilitySummaryCache;
        this.hasher = hasher;
        this.source = source;
    }

    public EligibilityResponse lookup(String memberRef) {
        return lookup(memberRef, null);
    }

    /** @param ttl per-entry TTL, or {@code null} for the region default (2 minutes). */
    public EligibilityResponse lookup(String memberRef, Duration ttl) {
        Tracked<EligibilityResult> t = tracked(cache, hasher.memberKey(memberRef), ttl, k -> source.loadEligibility(memberRef));
        EligibilityResult r = t.value();
        return new EligibilityResponse(memberRef, r.active(), r.planTier(), r.asOf(), t.servedFrom(),
                t.servedFrom() == ServedFrom.CACHE
                        ? "Served from cache (minimal derived result, TTL 2 min). Fine for display; call POST /api/eligibility/{id}/validate "
                                + "before acting on it."
                        : "Loaded from the simulated source and cached (minimal derived result, TTL 2 min).");
    }

    /**
     * Source validation: always asks the source ({@code requireFreshFromSource}), corrects the cache, and reports whether
     * the cached value had drifted from the source.
     */
    public ValidationResponse validate(String memberRef) {
        String key = hasher.memberKey(memberRef);
        EligibilityResult before = cache.peekEntry(key).map(CacheEntry::getValue).orElse(null);
        EligibilityResult fresh = cache.requireFreshFromSource(key, null, k -> source.loadEligibility(memberRef));
        boolean drift = before != null && !before.equals(fresh);
        Optional<EligibilityResult> after = cache.peekEntry(key).map(CacheEntry::getValue);
        boolean corrected = drift && after.isPresent() && after.get().equals(fresh);
        String note = drift
                ? "Drift detected: the cached value differed from the source. The cache was corrected; use the source value."
                : before == null ? "Nothing was cached; the value was loaded from the source and cached."
                : "The cached value matched the source.";
        return new ValidationResponse(memberRef, before, fresh, drift, corrected, note);
    }

    /** Demo only: simulates an upstream change of the member's status in the source. The cache is NOT touched. */
    public SourceChangeResponse changeSource(String memberRef) {
        EligibilityResult now = source.flipEligibility(memberRef);
        return new SourceChangeResponse(memberRef, now.active() ? "ACTIVE" : "INACTIVE",
                "Source status changed. Cached copies are untouched until they expire or are validated "
                        + "(POST /api/eligibility/{id}/validate shows the drift).");
    }

    record Tracked<V>(V value, ServedFrom servedFrom) {}

    /**
     * {@code getOrLoad} plus "did THIS call hit the source?". SOURCE only if our loader ran on the calling thread and its
     * result is what came back; cache hits and coalesced followers are CACHE.
     */
    static <V> Tracked<V> tracked(AcentraCache<String, V> cache, String key, Duration ttl, Function<String, V> loader) {
        Thread caller = Thread.currentThread();
        AtomicReference<V> loadedHere = new AtomicReference<>();
        V value = cache.getOrLoad(key, ttl, k -> {
            V v = loader.apply(k);
            if (Thread.currentThread() == caller) loadedHere.set(v);
            return v;
        });
        return new Tracked<>(value, loadedHere.get() == value && value != null ? ServedFrom.SOURCE : ServedFrom.CACHE);
    }
}

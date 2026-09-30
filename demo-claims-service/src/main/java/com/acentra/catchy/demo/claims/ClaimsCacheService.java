package com.acentra.catchy.demo.claims;

import com.acentra.cache.AcentraCache;
import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupResponse;
import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupSummary;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSet;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSetResponse;
import com.acentra.catchy.demo.claims.ClaimsModels.ServedFrom;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Read-through access to the two claims regions. All caching is done by the SDK ({@code getOrLoad} + stampede shield). */
@Service
public class ClaimsCacheService {

    private final AcentraCache<String, RuleSet> rules;
    private final AcentraCache<String, ProviderGroupSummary> providers;
    private final SimulatedSource source;

    public ClaimsCacheService(AcentraCache<String, RuleSet> claimRulesCache,
                              AcentraCache<String, ProviderGroupSummary> providerDirectoryCache, SimulatedSource source) {
        this.rules = claimRulesCache;
        this.providers = providerDirectoryCache;
        this.source = source;
    }

    public RuleSetResponse ruleSet(String ruleSetId) {
        return ruleSet(ruleSetId, null);
    }

    /** @param ttl per-entry TTL, or {@code null} for the region default (15 minutes). */
    public RuleSetResponse ruleSet(String ruleSetId, Duration ttl) {
        Tracked<RuleSet> t = tracked(rules, ruleSetId, ttl, source::loadRuleSet);
        return RuleSetResponse.of(t.value(), t.servedFrom());
    }

    public ProviderGroupResponse provider(String providerGroupId) {
        return provider(providerGroupId, null);
    }

    /** @param ttl per-entry TTL, or {@code null} for the region default (1 hour). */
    public ProviderGroupResponse provider(String providerGroupId, Duration ttl) {
        Tracked<ProviderGroupSummary> t = tracked(providers, providerGroupId, ttl, source::loadProvider);
        return ProviderGroupResponse.of(t.value(), t.servedFrom());
    }

    record Tracked<V>(V value, ServedFrom servedFrom) {}

    /**
     * {@code getOrLoad} plus "did THIS call hit the source?". It is SOURCE only if our loader ran on the calling thread
     * and its result is what came back; cache hits, coalesced followers and stale-while-revalidate answers are CACHE.
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

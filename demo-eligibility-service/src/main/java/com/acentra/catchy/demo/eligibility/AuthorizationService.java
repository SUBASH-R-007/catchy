package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheEntry;
import com.acentra.catchy.demo.eligibility.EligibilityModels.AuthorizationDecision;
import com.acentra.catchy.demo.eligibility.EligibilityModels.FinalizeResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.PreliminaryResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.ServedFrom;
import com.acentra.catchy.demo.eligibility.EligibilityModels.SourceChangeResponse;
import com.acentra.catchy.demo.eligibility.EligibilityService.Tracked;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * CRITICAL-risk region. The cache may support <b>preliminary display only</b>. A final action always goes through
 * {@code requireFreshFromSource}: mandatory source validation, never a cached answer.
 */
@Service
public class AuthorizationService {

    private final AcentraCache<String, AuthorizationDecision> cache;
    private final MemberKeyHasher hasher;
    private final SimulatedSource source;

    public AuthorizationService(AcentraCache<String, AuthorizationDecision> authorizationDecisionCache, MemberKeyHasher hasher,
                                SimulatedSource source) {
        this.cache = authorizationDecisionCache;
        this.hasher = hasher;
        this.source = source;
    }

    /** Cache allowed: the answer is explicitly preliminary and no final decision may be taken from it. */
    public PreliminaryResponse preliminary(String requestRef) {
        Tracked<AuthorizationDecision> t = EligibilityService.tracked(cache, hasher.authorizationKey(requestRef), null,
                k -> source.loadAuthorization(requestRef));
        return new PreliminaryResponse(requestRef, t.value().status(), true, false, t.servedFrom(),
                t.servedFrom() == ServedFrom.CACHE
                        ? "Preliminary display only (served from cache, TTL 60 s). A final action requires source validation: "
                                + "POST /api/authorization/{id}/finalize."
                        : "Preliminary display only (loaded from the source and cached for 60 s). A final action requires source "
                                + "validation: POST /api/authorization/{id}/finalize.");
    }

    /** Mandatory source validation: ALWAYS asks the source, corrects the cache, and reports any drift. */
    public FinalizeResponse finalizeDecision(String requestRef) {
        String key = hasher.authorizationKey(requestRef);
        AuthorizationDecision before = cache.peekEntry(key).map(CacheEntry::getValue).orElse(null);
        AuthorizationDecision fresh = cache.requireFreshFromSource(key, null, k -> source.loadAuthorization(requestRef));
        boolean drift = before != null && !before.equals(fresh);
        Optional<AuthorizationDecision> after = cache.peekEntry(key).map(CacheEntry::getValue);
        boolean corrected = drift && after.isPresent() && after.get().equals(fresh);
        String note = drift
                ? "The preliminary (cached) status had drifted from the source; the final decision uses the validated source value."
                : "Final decision validated against the source.";
        return new FinalizeResponse(requestRef, fresh.status(), false, true, true, drift, corrected, note);
    }

    /** Demo only: simulates an upstream decision change in the source. The cache is NOT touched. */
    public SourceChangeResponse changeSource(String requestRef) {
        AuthorizationDecision now = source.changeAuthorization(requestRef);
        return new SourceChangeResponse(requestRef, now.status(),
                "Source decision changed. The cached preliminary status is untouched; finalize will detect the drift.");
    }
}

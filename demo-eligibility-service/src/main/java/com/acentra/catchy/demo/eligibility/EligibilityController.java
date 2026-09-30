package com.acentra.catchy.demo.eligibility;

import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.FinalizeResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.PreliminaryResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.SourceChangeResponse;
import com.acentra.catchy.demo.eligibility.EligibilityModels.ValidationResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/** Eligibility and authorization endpoints. Ids are synthetic ({@code SYN-000123}); they are never logged. */
@RestController
public class EligibilityController {

    private final EligibilityService eligibility;
    private final AuthorizationService authorization;

    public EligibilityController(EligibilityService eligibility, AuthorizationService authorization) {
        this.eligibility = eligibility;
        this.authorization = authorization;
    }

    @GetMapping("/api/eligibility/{syntheticMemberId}")
    public EligibilityResponse eligibility(@PathVariable String syntheticMemberId) {
        return eligibility.lookup(InvalidRequestException.requireSyntheticId(syntheticMemberId, "syntheticMemberId"));
    }

    /** Source validation example: always asks the source, reports drift, corrects the cache. */
    @PostMapping("/api/eligibility/{syntheticMemberId}/validate")
    public ValidationResponse validate(@PathVariable String syntheticMemberId) {
        return eligibility.validate(InvalidRequestException.requireSyntheticId(syntheticMemberId, "syntheticMemberId"));
    }

    /** Preliminary display only: cache allowed, final decision NOT allowed. */
    @GetMapping("/api/authorization/{syntheticRequestId}/preliminary")
    public PreliminaryResponse preliminary(@PathVariable String syntheticRequestId) {
        return authorization.preliminary(InvalidRequestException.requireSyntheticId(syntheticRequestId, "syntheticRequestId"));
    }

    /** Final action: ALWAYS validated against the source (requireFreshFromSource). */
    @PostMapping("/api/authorization/{syntheticRequestId}/finalize")
    public FinalizeResponse finalizeDecision(@PathVariable String syntheticRequestId) {
        return authorization.finalizeDecision(InvalidRequestException.requireSyntheticId(syntheticRequestId, "syntheticRequestId"));
    }

    /** Demo only: flips the synthetic source status so a later {@code validate} shows drift. */
    @PostMapping("/api/demo/eligibility/source/{syntheticMemberId}/change")
    public SourceChangeResponse changeMemberSource(@PathVariable String syntheticMemberId) {
        return eligibility.changeSource(InvalidRequestException.requireSyntheticId(syntheticMemberId, "syntheticMemberId"));
    }

    /** Demo only: changes the synthetic source decision so a later {@code finalize} shows drift vs the cached preliminary. */
    @PostMapping("/api/demo/eligibility/authorization-source/{syntheticRequestId}/change")
    public SourceChangeResponse changeAuthorizationSource(@PathVariable String syntheticRequestId) {
        return authorization.changeSource(InvalidRequestException.requireSyntheticId(syntheticRequestId, "syntheticRequestId"));
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", CacheConfig.APPLICATION);
    }
}

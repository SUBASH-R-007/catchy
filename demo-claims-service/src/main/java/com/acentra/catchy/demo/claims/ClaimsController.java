package com.acentra.catchy.demo.claims;

import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupResponse;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSetResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ClaimsController {

    private final ClaimsCacheService service;

    public ClaimsController(ClaimsCacheService service) {
        this.service = service;
    }

    @GetMapping("/api/claims/rules/{ruleSetId}")
    public RuleSetResponse rules(@PathVariable String ruleSetId) {
        return service.ruleSet(InvalidRequestException.requireId(ruleSetId, "ruleSetId"));
    }

    @GetMapping("/api/providers/{providerGroupId}")
    public ProviderGroupResponse provider(@PathVariable String providerGroupId) {
        return service.provider(InvalidRequestException.requireId(providerGroupId, "providerGroupId"));
    }

    @GetMapping("/api/health")
    public Map<String, String> health() {
        return Map.of("status", "UP", "service", CacheConfig.APPLICATION);
    }
}

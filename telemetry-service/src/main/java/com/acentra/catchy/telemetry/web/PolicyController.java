package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.dto.PolicyDtos.CreatePolicyChangeRequest;
import com.acentra.catchy.telemetry.dto.PolicyDtos.DecisionRequest;
import com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest;
import com.acentra.catchy.telemetry.dto.PolicyDtos.Recommendation;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.service.AuditAction;
import com.acentra.catchy.telemetry.service.AuditService;
import com.acentra.catchy.telemetry.service.PolicyChangeService;
import com.acentra.catchy.telemetry.service.RecommendationService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Policy Arena recommendations and the policy change request workflow. */
@RestController
@RequestMapping("/api/v1")
public class PolicyController {

    private final RecommendationService recommendations;
    private final PolicyChangeService policyChanges;
    private final AuditService audit;

    public PolicyController(RecommendationService recommendations, PolicyChangeService policyChanges, AuditService audit) {
        this.recommendations = recommendations;
        this.policyChanges = policyChanges;
        this.audit = audit;
    }

    @GetMapping("/applications/{applicationId}/recommendations")
    public List<Recommendation> recommendations(@PathVariable Long applicationId) {
        return recommendations.refresh(applicationId, false);
    }

    @PostMapping("/applications/{applicationId}/recommendations/evaluate")
    public List<Recommendation> evaluate(@PathVariable Long applicationId) {
        List<Recommendation> result = recommendations.refresh(applicationId, true);
        audit.success(AuditAction.RECOMMENDATION_EVALUATED, "APPLICATION", String.valueOf(applicationId), applicationId,
                "Evaluated recommendations for " + result.size() + " region(s)");
        return result;
    }

    @PostMapping("/applications/{applicationId}/policy-change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyChangeRequest create(@PathVariable Long applicationId, @Valid @RequestBody CreatePolicyChangeRequest body,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return policyChanges.create(applicationId, body, user);
    }

    @GetMapping("/applications/{applicationId}/policy-change-requests")
    public List<PolicyChangeRequest> list(@PathVariable Long applicationId) {
        return policyChanges.list(applicationId);
    }

    @PostMapping("/policy-change-requests/{requestId}/approve")
    public PolicyChangeRequest approve(@PathVariable Long requestId, @Valid @RequestBody(required = false) DecisionRequest body,
                                       @AuthenticationPrincipal AuthenticatedUser user) {
        return policyChanges.approve(requestId, body == null ? null : body.note(), user);
    }

    @PostMapping("/policy-change-requests/{requestId}/reject")
    public PolicyChangeRequest reject(@PathVariable Long requestId, @Valid @RequestBody(required = false) DecisionRequest body,
                                      @AuthenticationPrincipal AuthenticatedUser user) {
        return policyChanges.reject(requestId, body == null ? null : body.note(), user);
    }
}

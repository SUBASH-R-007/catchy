package com.acentra.catchy.telemetry.service;

import com.acentra.cache.EvictionPolicy;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.PolicyChangeRequest;
import com.acentra.catchy.telemetry.domain.PolicyRequestRepository;
import com.acentra.catchy.telemetry.domain.RecommendationRepository;
import com.acentra.catchy.telemetry.dto.PolicyDtos.CreatePolicyChangeRequest;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.service.AggregationService.RegionAggregate;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy change workflow: PENDING, then APPROVED and APPLIED, or REJECTED. Automation never changes a policy: an
 * approved request is only delivered to the SDK through the control endpoint, and becomes APPLIED when a later
 * snapshot reports the region running the requested policy. Every transition is audited.
 */
@Service
public class PolicyChangeService {

    private final PolicyRequestRepository requests;
    private final ApplicationRepository applications;
    private final RecommendationRepository recommendations;
    private final AggregationService aggregation;
    private final AuditService audit;
    private final Clock clock;

    public PolicyChangeService(PolicyRequestRepository requests, ApplicationRepository applications,
                               RecommendationRepository recommendations, AggregationService aggregation,
                               AuditService audit, Clock clock) {
        this.requests = requests;
        this.applications = applications;
        this.recommendations = recommendations;
        this.aggregation = aggregation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest create(Long applicationId,
                                                                                  CreatePolicyChangeRequest body,
                                                                                  AuthenticatedUser actor) {
        ApplicationService app = requireApplication(applicationId);
        RegionAggregate region = aggregation.forRegion(applicationId, body.cacheRegion())
                .orElseThrow(() -> ApiException.notFound("Cache region not found"));
        if (region.activePolicy() == body.requestedPolicy()) {
            throw ApiException.badRequest("requestedPolicy must differ from the current policy (" + region.activePolicy() + ")");
        }
        if (requests.existsByApplicationIdAndCacheRegionAndStatus(applicationId, body.cacheRegion(), PolicyChangeRequest.PENDING)) {
            throw ApiException.conflict("A policy change request for this region is already pending");
        }
        if (body.recommendationId() != null) {
            boolean ok = recommendations.findById(body.recommendationId())
                    .map(r -> r.applicationId.equals(applicationId)).orElse(false);
            if (!ok) throw ApiException.badRequest("recommendationId does not refer to a recommendation of this application");
        }
        PolicyChangeRequest r = new PolicyChangeRequest();
        r.applicationId = applicationId;
        r.cacheRegion = body.cacheRegion();
        r.currentPolicy = region.activePolicy().name();
        r.requestedPolicy = body.requestedPolicy().name();
        r.reason = body.reason() == null || body.reason().isBlank() ? "Policy change requested from the dashboard" : body.reason().trim();
        r.status = PolicyChangeRequest.PENDING;
        r.requestedBy = actor.username();
        r.createdAt = Times.now(clock);
        r.recommendationId = body.recommendationId();
        r = requests.save(r);
        audit.success(AuditAction.POLICY_CHANGE_REQUESTED, "POLICY_CHANGE_REQUEST", String.valueOf(r.id), applicationId,
                "Requested " + r.currentPolicy + " -> " + r.requestedPolicy + " for region " + r.cacheRegion);
        return toDto(r, app.name);
    }

    @Transactional(readOnly = true)
    public List<com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest> list(Long applicationId) {
        ApplicationService app = requireApplication(applicationId);
        return requests.findByApplicationIdOrderByIdDesc(applicationId).stream().map(r -> toDto(r, app.name)).toList();
    }

    @Transactional
    public com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest approve(Long requestId, String note, AuthenticatedUser actor) {
        return decide(requestId, note, actor, true);
    }

    @Transactional
    public com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest reject(Long requestId, String note, AuthenticatedUser actor) {
        return decide(requestId, note, actor, false);
    }

    private com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest decide(Long requestId, String note,
                                                                                   AuthenticatedUser actor, boolean approve) {
        PolicyChangeRequest r = requests.findByIdForUpdate(requestId)
                .orElseThrow(() -> ApiException.notFound("Policy change request not found"));
        ApplicationService app = requireApplication(r.applicationId);
        if (!actor.isAdmin() && r.requestedBy.equals(actor.username())) {
            audit.denied(AuditAction.ACCESS_DENIED, "POLICY_CHANGE_REQUEST", String.valueOf(r.id), r.applicationId,
                    "Denied: an engineer cannot " + (approve ? "approve" : "reject") + " their own policy change request");
            throw ApiException.forbidden("You cannot " + (approve ? "approve" : "reject") + " your own request");
        }
        if (!PolicyChangeRequest.PENDING.equals(r.status)) {
            throw ApiException.conflict("Policy change request is not pending (status " + r.status + ")");
        }
        r.status = approve ? PolicyChangeRequest.APPROVED : PolicyChangeRequest.REJECTED;
        r.decidedBy = actor.username();
        r.decidedAt = Times.now(clock);
        r.decisionNote = note == null || note.isBlank() ? null : note.trim();
        audit.success(approve ? AuditAction.POLICY_CHANGE_APPROVED : AuditAction.POLICY_CHANGE_REJECTED,
                "POLICY_CHANGE_REQUEST", String.valueOf(r.id), r.applicationId,
                (approve ? "Approved " : "Rejected ") + r.currentPolicy + " -> " + r.requestedPolicy + " for region " + r.cacheRegion);
        return toDto(r, app.name);
    }

    /**
     * Called for every received snapshot: an APPROVED request whose policy the region now reports as active becomes
     * APPLIED (and the cooldown clock starts from that moment).
     */
    @Transactional
    public void onSnapshot(Long applicationId, String region, EvictionPolicy reportedPolicy) {
        List<PolicyChangeRequest> approved = requests.findByApplicationIdAndCacheRegionAndStatus(
                applicationId, region, PolicyChangeRequest.APPROVED);
        for (PolicyChangeRequest r : approved) {
            if (r.requestedPolicy.equals(reportedPolicy.name())) {
                Instant now = Times.now(clock);
                r.status = PolicyChangeRequest.APPLIED;
                r.appliedAt = now;
                audit.successAs(AuditService.Actor.system(), AuditAction.POLICY_CHANGE_APPLIED, "POLICY_CHANGE_REQUEST",
                        String.valueOf(r.id), applicationId,
                        "Region " + region + " now reports policy " + r.requestedPolicy + " (request " + r.id + ")");
            }
        }
    }

    private ApplicationService requireApplication(Long id) {
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("Application not found"));
    }

    static com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest toDto(PolicyChangeRequest r, String applicationName) {
        return new com.acentra.catchy.telemetry.dto.PolicyDtos.PolicyChangeRequest(r.id, r.applicationId, applicationName,
                r.cacheRegion, EvictionPolicy.valueOf(r.currentPolicy), EvictionPolicy.valueOf(r.requestedPolicy), r.reason,
                r.status, r.requestedBy, r.decidedBy, r.decisionNote, r.createdAt, r.decidedAt, r.appliedAt, r.recommendationId);
    }
}

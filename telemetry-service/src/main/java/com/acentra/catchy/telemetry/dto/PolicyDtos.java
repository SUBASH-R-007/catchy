package com.acentra.catchy.telemetry.dto;

import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Recommendations, policy change requests and region configuration (docs/api.md). */
public final class PolicyDtos {
    private PolicyDtos() {}

    public record Recommendation(
            Long id, Long applicationId, String applicationName, String cacheRegion,
            EvictionPolicy currentPolicy, EvictionPolicy recommendedPolicy, String action, String summary,
            double lruShadowHitRate, double lfuShadowHitRate, double improvementPercent, int confidence,
            String reason, String aiExplanation, long sampleSize, boolean minimumSampleMet, boolean cooldownActive,
            Instant cooldownEndsAt, boolean approvalRequired, Instant createdAt, Long pendingRequestId) {}

    public record CreatePolicyChangeRequest(
            @NotBlank String cacheRegion,
            @NotNull EvictionPolicy requestedPolicy,
            @Size(max = 500) String reason,
            Long recommendationId) {}

    public record DecisionRequest(@Size(max = 500) String note) {}

    public record PolicyChangeRequest(
            Long id, Long applicationId, String applicationName, String cacheRegion,
            EvictionPolicy currentPolicy, EvictionPolicy requestedPolicy, String reason, String status,
            String requestedBy, String decidedBy, String decisionNote, Instant createdAt, Instant decidedAt,
            Instant appliedAt, Long recommendationId) {}

    public record UpdateRegionConfigRequest(
            @Min(1) @Max(10_000_000) Long maximumEntries,
            @Min(1024) Long maximumMemoryBytes,
            @Min(1000) Long defaultTtlMs,
            @Size(max = 300) String reason) {}

    public record Reported(long maximumEntries, long maximumMemoryBytes, long defaultTtlMs, EvictionPolicy activePolicy) {}

    public record Desired(Long maximumEntries, Long maximumMemoryBytes, Long defaultTtlMs) {}

    public record RegionConfig(
            String cacheRegion, CacheRiskLevel riskLevel, Reported reported, Desired desired, boolean pending,
            long tuningVersion, String updatedBy, Instant updatedAt) {}
}

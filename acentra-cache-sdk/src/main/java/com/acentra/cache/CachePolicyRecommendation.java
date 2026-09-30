package com.acentra.cache;

import java.time.Instant;

/**
 * Deterministic Policy Arena output. Never applied automatically: {@code approvalRequired} is always true.
 *
 * @param improvementPercent simulated hit rate of the alternative policy minus that of the current one (may be negative)
 */
public record CachePolicyRecommendation(
        EvictionPolicy currentPolicy,
        EvictionPolicy recommendedPolicy,
        Action action,
        double lruShadowHitRate,
        double lfuShadowHitRate,
        double improvementPercent,
        int confidence,
        String reason,
        long sampleSize,
        boolean minimumSampleMet,
        boolean cooldownActive,
        Instant cooldownEndsAt,
        boolean approvalRequired) {

    public enum Action { SWITCH, KEEP }

    /** "Switch to LFU" or "Keep LRU". */
    public String summary() {
        return (action == Action.SWITCH ? "Switch to " : "Keep ") + recommendedPolicy;
    }
}

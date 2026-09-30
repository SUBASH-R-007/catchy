package com.acentra.cache.telemetry;

import com.acentra.cache.CacheAction;
import com.acentra.cache.CacheDecisionEvent;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import java.time.Instant;

/** Safe telemetry event (wire shape in docs/api.md). Contains no raw keys, values or PHI by construction. */
public record TelemetryEvent(
        Instant timestamp,
        String applicationName,
        String environment,
        String cacheRegion,
        CacheAction action,
        String keyFingerprint,
        String reason,
        EvictionPolicy policy,
        long frequency,
        long lastAccessAgeMs,
        long remainingTtlMs,
        long estimatedEntrySizeBytes,
        int cacheSizeBefore,
        int cacheSizeAfter,
        long memoryBeforeBytes,
        long memoryAfterBytes,
        boolean valueReturned,
        EventSeverity severity,
        double latencyMs) {

    public static TelemetryEvent from(CacheDecisionEvent e) {
        String reason = e.reason();
        if (reason != null && reason.length() > 400) reason = reason.substring(0, 397) + "...";
        return new TelemetryEvent(
                e.timestamp(), e.applicationName(), e.environment(), e.cacheRegion(), e.action(), e.keyFingerprint(),
                reason, e.activePolicy(), e.frequencyAtDecision(), e.lastAccessAgeMs(), e.remainingTtlMs(),
                e.estimatedEntrySizeBytes(), e.cacheSizeBefore(), e.cacheSizeAfter(), e.memoryBeforeBytes(),
                e.memoryAfterBytes(), e.valueReturned(), e.severity(), e.latencyMs());
    }
}

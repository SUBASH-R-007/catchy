package com.acentra.cache;

import java.time.Instant;

/**
 * One explainable cache decision (Eviction X-ray). Contains only safe metadata: a key <i>fingerprint</i>,
 * never the raw key or the cached value.
 */
public record CacheDecisionEvent(
        long sequence,
        Instant timestamp,
        String applicationName,
        String environment,
        String cacheRegion,
        String keyFingerprint,
        CacheAction action,
        String reason,
        EvictionPolicy activePolicy,
        long frequencyAtDecision,
        long lastAccessAgeMs,
        long remainingTtlMs,
        long estimatedEntrySizeBytes,
        int cacheSizeBefore,
        int cacheSizeAfter,
        long memoryBeforeBytes,
        long memoryAfterBytes,
        boolean valueReturned,
        EventSeverity severity,
        double latencyMs) {}

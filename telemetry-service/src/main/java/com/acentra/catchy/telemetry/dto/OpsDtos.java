package com.acentra.catchy.telemetry.dto;

import com.acentra.cache.CacheAction;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.List;

/** Events, audit log, simulation, ingestion acknowledgement and errors (docs/api.md). */
public final class OpsDtos {
    private OpsDtos() {}

    public record Event(
            Long id, Instant timestamp, String applicationName, String environment, String cacheRegion,
            CacheAction action, String keyFingerprint, String reason, EvictionPolicy policy, long frequency,
            long lastAccessAgeMs, long remainingTtlMs, long estimatedEntrySizeBytes, int cacheSizeBefore,
            int cacheSizeAfter, long memoryBeforeBytes, long memoryAfterBytes, boolean valueReturned,
            EventSeverity severity, double latencyMs) {}

    public record AuditLog(
            Long id, Instant timestamp, String actor, String actorRole, String action, String targetType,
            String targetId, Long applicationId, String details, String outcome) {}

    public record IngestAck(int acceptedEvents, int acceptedSnapshots) {}

    public record ErrorResponse(Instant timestamp, int status, String error, String message, String path,
                                List<String> details) {}

    public record SimulationRequest(@Min(50) @Max(20_000) Integer requests) {}

    public record SimulationStep(String name, String description, String detail) {}

    public record ComparisonRow(String pattern, double lruHitRate, double lfuHitRate, String winner, String interpretation) {}

    public record SimulationResult(
            String simulationId, String kind, Long applicationId, List<String> cacheRegions, Instant startedAt,
            long durationMs, String summary, List<SimulationStep> steps, long hits, long misses, double hitRate,
            long evictions, long expirations, List<ComparisonRow> comparison) {}
}

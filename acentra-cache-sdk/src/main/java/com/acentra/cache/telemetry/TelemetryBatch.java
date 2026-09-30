package com.acentra.cache.telemetry;

import java.time.Instant;
import java.util.List;

/** Body of {@code POST /api/v1/telemetry/events/batch}. */
public record TelemetryBatch(
        String applicationName,
        String environment,
        String instanceId,
        String sdkVersion,
        Instant sentAt,
        List<TelemetryEvent> events,
        List<RegionSnapshot> snapshots) {}

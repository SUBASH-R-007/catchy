package com.acentra.catchy.telemetry.domain;

import java.time.Instant;

/** Slim projection of a snapshot row used to build timelines without loading the full entity. */
public record SnapshotPoint(
        Long applicationId,
        String cacheRegion,
        String instanceId,
        Instant capturedAt,
        long hits,
        long misses,
        long puts,
        long evictions,
        long expirations) {}

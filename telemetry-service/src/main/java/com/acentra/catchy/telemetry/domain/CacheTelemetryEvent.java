package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Safe event metadata (fingerprints, never raw keys or values). Written through batched JDBC, read through JPA. */
@Entity
@Table(name = "cache_telemetry_event")
public class CacheTelemetryEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long applicationId;
    @Column(nullable = false, length = 63)
    public String cacheRegion;
    @Column(length = 80)
    public String instanceId;
    @Column(nullable = false)
    public Instant eventTimestamp;
    @Column(nullable = false)
    public Instant receivedAt;
    @Column(nullable = false, length = 32)
    public String action;
    @Column(length = 80)
    public String keyFingerprint;
    @Column(length = 400)
    public String reason;
    @Column(nullable = false, length = 8)
    public String policy;
    public long frequency;
    public long lastAccessAgeMs;
    public long remainingTtlMs;
    public long estimatedEntrySizeBytes;
    public int cacheSizeBefore;
    public int cacheSizeAfter;
    public long memoryBeforeBytes;
    public long memoryAfterBytes;
    public boolean valueReturned;
    @Column(nullable = false, length = 16)
    public String severity;
    public double latencyMs;
}

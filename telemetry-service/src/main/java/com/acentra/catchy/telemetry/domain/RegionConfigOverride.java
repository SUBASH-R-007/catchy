package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Desired cache tuning for a region, set by an administrator and delivered to the SDK via /telemetry/control. */
@Entity
@Table(name = "region_config_override")
public class RegionConfigOverride {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long applicationId;
    @Column(nullable = false, length = 63)
    public String cacheRegion;
    public Long maximumEntries;
    public Long maximumMemoryBytes;
    public Long defaultTtlMs;
    public long tuningVersion;
    public long appliedVersion;
    @Column(length = 300)
    public String reason;
    @Column(nullable = false, length = 64)
    public String updatedBy;
    @Column(nullable = false)
    public Instant updatedAt;
}

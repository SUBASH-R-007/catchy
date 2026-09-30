package com.acentra.cache.telemetry;

import com.acentra.cache.EvictionPolicy;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Approved engineer decisions returned by {@code GET /api/v1/telemetry/control}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ControlResponse(List<RegionControl> regions) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegionControl(
            String cacheRegion, EvictionPolicy desiredPolicy, Long policyRequestId, Tuning tuning, Long tuningVersion) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Tuning(Long maximumEntries, Long maximumMemoryBytes, Long defaultTtlMs) {}
}

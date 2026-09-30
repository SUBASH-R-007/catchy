package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.CacheMetricsSnapshot;
import com.acentra.catchy.telemetry.domain.RegionConfigOverride;
import com.acentra.catchy.telemetry.domain.RegionConfigRepository;
import com.acentra.catchy.telemetry.dto.PolicyDtos.Desired;
import com.acentra.catchy.telemetry.dto.PolicyDtos.RegionConfig;
import com.acentra.catchy.telemetry.dto.PolicyDtos.Reported;
import com.acentra.catchy.telemetry.dto.PolicyDtos.UpdateRegionConfigRequest;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.service.AggregationService.RegionAggregate;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrator-desired cache tuning per region. PUT stores the desired values and bumps {@code tuningVersion}; the
 * SDK picks them up from /telemetry/control, and CONFIG_CHANGE_APPLIED is audited once a snapshot matches them.
 */
@Service
public class RegionConfigService {

    private final RegionConfigRepository configs;
    private final ApplicationRepository applications;
    private final AggregationService aggregation;
    private final AuditService audit;
    private final Clock clock;

    public RegionConfigService(RegionConfigRepository configs, ApplicationRepository applications,
                               AggregationService aggregation, AuditService audit, Clock clock) {
        this.configs = configs;
        this.applications = applications;
        this.aggregation = aggregation;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RegionConfig get(Long applicationId, String region) {
        requireApplication(applicationId);
        RegionAggregate agg = requireRegion(applicationId, region);
        return toDto(agg, configs.findByApplicationIdAndCacheRegion(applicationId, region).orElse(null));
    }

    @Transactional
    public RegionConfig update(Long applicationId, String region, UpdateRegionConfigRequest body, AuthenticatedUser actor) {
        requireApplication(applicationId);
        RegionAggregate agg = requireRegion(applicationId, region);
        if (body.maximumEntries() == null && body.maximumMemoryBytes() == null && body.defaultTtlMs() == null) {
            throw ApiException.badRequest("Validation failed",
                    List.of("body: at least one of maximumEntries, maximumMemoryBytes, defaultTtlMs is required"));
        }
        RegionConfigOverride o = configs.findByApplicationIdAndCacheRegion(applicationId, region).orElse(null);
        if (o == null) {
            o = new RegionConfigOverride();
            o.applicationId = applicationId;
            o.cacheRegion = region;
        }
        List<String> changed = new ArrayList<>();
        if (body.maximumEntries() != null) {
            o.maximumEntries = body.maximumEntries();
            changed.add("maximumEntries=" + body.maximumEntries());
        }
        if (body.maximumMemoryBytes() != null) {
            o.maximumMemoryBytes = body.maximumMemoryBytes();
            changed.add("maximumMemoryBytes=" + body.maximumMemoryBytes());
        }
        if (body.defaultTtlMs() != null) {
            o.defaultTtlMs = body.defaultTtlMs();
            changed.add("defaultTtlMs=" + body.defaultTtlMs());
        }
        o.tuningVersion = o.tuningVersion + 1;
        o.reason = body.reason() == null || body.reason().isBlank() ? null : body.reason().trim();
        o.updatedBy = actor.username();
        o.updatedAt = Times.now(clock);
        o = configs.save(o);
        audit.success(AuditAction.CONFIG_CHANGE_REQUESTED, "REGION_CONFIG", region, applicationId,
                "Requested " + String.join(", ", changed) + " for region " + region + " (version " + o.tuningVersion + ")"
                        + (o.reason == null ? "" : ": " + o.reason));
        return toDto(agg, o);
    }

    /** Called for every received snapshot: marks the desired tuning as applied once the SDK reports matching values. */
    @Transactional
    public void onSnapshot(Long applicationId, CacheMetricsSnapshot snapshot) {
        Optional<RegionConfigOverride> found = configs.findByApplicationIdAndCacheRegion(applicationId, snapshot.cacheRegion);
        if (found.isEmpty()) return;
        RegionConfigOverride o = found.get();
        if (o.appliedVersion >= o.tuningVersion) return;
        boolean matches = (o.maximumEntries == null || o.maximumEntries == snapshot.capacity)
                && (o.maximumMemoryBytes == null || o.maximumMemoryBytes == snapshot.maximumMemoryBytes)
                && (o.defaultTtlMs == null || o.defaultTtlMs == snapshot.defaultTtlMs);
        if (matches) {
            o.appliedVersion = o.tuningVersion;
            audit.successAs(AuditService.Actor.system(), AuditAction.CONFIG_CHANGE_APPLIED, "REGION_CONFIG",
                    snapshot.cacheRegion, applicationId,
                    "Region " + snapshot.cacheRegion + " now reports the desired configuration (version " + o.tuningVersion + ")");
        }
    }

    private static RegionConfig toDto(RegionAggregate agg, RegionConfigOverride o) {
        Reported reported = new Reported(agg.reportedMaxEntries(), agg.reportedMaxMemoryBytes(), agg.defaultTtlMs(), agg.activePolicy());
        if (o == null) {
            return new RegionConfig(agg.cacheRegion(), agg.riskLevel(), reported, null, false, 0, null, null);
        }
        boolean pending = (o.maximumEntries != null && o.maximumEntries != agg.reportedMaxEntries())
                || (o.maximumMemoryBytes != null && o.maximumMemoryBytes != agg.reportedMaxMemoryBytes())
                || (o.defaultTtlMs != null && o.defaultTtlMs != agg.defaultTtlMs());
        return new RegionConfig(agg.cacheRegion(), agg.riskLevel(), reported,
                new Desired(o.maximumEntries, o.maximumMemoryBytes, o.defaultTtlMs), pending, o.tuningVersion,
                o.updatedBy, o.updatedAt);
    }

    private void requireApplication(Long id) {
        if (!applications.existsById(id)) throw ApiException.notFound("Application not found");
    }

    private RegionAggregate requireRegion(Long applicationId, String region) {
        return aggregation.forRegion(applicationId, region).orElseThrow(() -> ApiException.notFound("Cache region not found"));
    }
}

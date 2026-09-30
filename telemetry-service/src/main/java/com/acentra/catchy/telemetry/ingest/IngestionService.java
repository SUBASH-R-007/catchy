package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.CacheMetricsSnapshot;
import com.acentra.catchy.telemetry.domain.SnapshotRepository;
import com.acentra.catchy.telemetry.dto.OpsDtos.IngestAck;
import com.acentra.catchy.telemetry.security.IngestPrincipal;
import com.acentra.catchy.telemetry.service.AuditAction;
import com.acentra.catchy.telemetry.service.AuditService;
import com.acentra.catchy.telemetry.service.PolicyChangeService;
import com.acentra.catchy.telemetry.service.RegionConfigService;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists validated safe telemetry. The API key's application is authoritative: a payload that names a different
 * application or environment is refused with 403. Events are written with batched JDBC inserts; snapshots keep the
 * full history and flag the newest row per (application, region, instance).
 */
@Service
public class IngestionService {
    static final String DEFAULT_INSTANCE = "default";

    private static final String INSERT_EVENT = "INSERT INTO cache_telemetry_event (application_id, cache_region, instance_id, "
            + "event_timestamp, received_at, action, key_fingerprint, reason, policy, frequency, last_access_age_ms, "
            + "remaining_ttl_ms, estimated_entry_size_bytes, cache_size_before, cache_size_after, memory_before_bytes, "
            + "memory_after_bytes, value_returned, severity, latency_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";

    private final JdbcTemplate jdbc;
    private final SnapshotRepository snapshots;
    private final ApplicationRepository applications;
    private final PolicyChangeService policyChanges;
    private final RegionConfigService regionConfigs;
    private final AuditService audit;
    private final Clock clock;

    public IngestionService(JdbcTemplate jdbc, SnapshotRepository snapshots, ApplicationRepository applications,
                            PolicyChangeService policyChanges, RegionConfigService regionConfigs, AuditService audit,
                            Clock clock) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
        this.applications = applications;
        this.policyChanges = policyChanges;
        this.regionConfigs = regionConfigs;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public IngestAck ingest(IngestPrincipal principal, TelemetryBatch batch) {
        checkOwnership(principal, batch.applicationName(), batch.environment());
        List<TelemetryEvent> events = batch.events() == null ? List.of() : batch.events();
        for (TelemetryEvent e : events) checkOwnership(principal, e.applicationName(), e.environment());
        String instance = batch.instanceId() == null ? DEFAULT_INSTANCE : batch.instanceId();
        return store(principal.applicationId(), instance, events, batch.snapshots() == null ? List.of() : batch.snapshots());
    }

    @Transactional
    public IngestAck ingestEvent(IngestPrincipal principal, TelemetryEvent event) {
        checkOwnership(principal, event.applicationName(), event.environment());
        return store(principal.applicationId(), DEFAULT_INSTANCE, List.of(event), List.of());
    }

    /** In-process ingestion (simulations): no API key involved, the application is chosen by the service itself. */
    @Transactional
    public IngestAck ingestInternal(Long applicationId, String instanceId, List<TelemetryEvent> events,
                                    List<RegionSnapshot> regionSnapshots) {
        return store(applicationId, instanceId, events, regionSnapshots);
    }

    private IngestAck store(Long applicationId, String instanceId, List<TelemetryEvent> events, List<RegionSnapshot> regionSnapshots) {
        Instant now = Times.now(clock);
        if (!events.isEmpty()) insertEvents(applicationId, instanceId, events, now);
        for (RegionSnapshot s : regionSnapshots) saveSnapshot(applicationId, instanceId, s, now);
        applications.touchTelemetry(applicationId, now, (int) snapshots.countRegions(applicationId));
        return new IngestAck(events.size(), regionSnapshots.size());
    }

    private void insertEvents(Long applicationId, String instanceId, List<TelemetryEvent> events, Instant received) {
        jdbc.batchUpdate(INSERT_EVENT, events, 100, (ps, e) -> {
            ps.setLong(1, applicationId);
            ps.setString(2, e.cacheRegion());
            ps.setString(3, instanceId);
            ps.setObject(4, e.timestamp().atOffset(ZoneOffset.UTC));
            ps.setObject(5, received.atOffset(ZoneOffset.UTC));
            ps.setString(6, e.action().name());
            ps.setString(7, e.keyFingerprint());
            ps.setString(8, e.reason() != null && e.reason().length() > 400 ? e.reason().substring(0, 400) : e.reason());
            ps.setString(9, e.policy().name());
            ps.setLong(10, e.frequency());
            ps.setLong(11, e.lastAccessAgeMs());
            ps.setLong(12, e.remainingTtlMs());
            ps.setLong(13, e.estimatedEntrySizeBytes());
            ps.setInt(14, e.cacheSizeBefore());
            ps.setInt(15, e.cacheSizeAfter());
            ps.setLong(16, e.memoryBeforeBytes());
            ps.setLong(17, e.memoryAfterBytes());
            ps.setBoolean(18, e.valueReturned());
            ps.setString(19, e.severity() == null ? "INFO" : e.severity().name());
            ps.setDouble(20, e.latencyMs());
        });
    }

    private void saveSnapshot(Long applicationId, String instanceId, RegionSnapshot s, Instant received) {
        Optional<CacheMetricsSnapshot> previous = snapshots
                .findFirstByApplicationIdAndCacheRegionAndInstanceIdAndLatestTrueOrderByCapturedAtDesc(
                        applicationId, s.cacheRegion(), instanceId);
        Instant captured = s.capturedAt().truncatedTo(ChronoUnit.MILLIS);
        if (previous.isPresent() && previous.get().capturedAt.equals(captured)) return; // re-sent snapshot
        boolean newest = previous.isEmpty() || captured.isAfter(previous.get().capturedAt);
        CacheMetricsSnapshot row = SnapshotMapper.toEntity(applicationId, instanceId, s, received);
        row.latest = newest;
        if (newest) previous.ifPresent(p -> p.latest = false);
        snapshots.save(row);
        if (newest) {
            policyChanges.onSnapshot(applicationId, s.cacheRegion(), s.activePolicy());
            regionConfigs.onSnapshot(applicationId, row);
        }
    }

    /** 403 when the payload names another application or environment than the one the API key belongs to. */
    private void checkOwnership(IngestPrincipal p, String applicationName, String environment) {
        boolean mismatch = (applicationName != null && !applicationName.equals(p.applicationName()))
                || (environment != null && !environment.equals(p.environment()));
        if (mismatch) {
            audit.denied(AuditAction.ACCESS_DENIED, "TELEMETRY", null, p.applicationId(),
                    "Denied telemetry: payload application/environment does not match the API key's application");
            throw ApiException.forbidden("Telemetry payload does not match the application of this API key");
        }
    }
}

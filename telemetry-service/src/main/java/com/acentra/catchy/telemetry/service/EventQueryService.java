package com.acentra.catchy.telemetry.service;

import com.acentra.cache.CacheAction;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.CacheTelemetryEvent;
import com.acentra.catchy.telemetry.domain.EventRepository;
import com.acentra.catchy.telemetry.domain.SnapshotRepository;
import com.acentra.catchy.telemetry.dto.OpsDtos.Event;
import com.acentra.catchy.telemetry.web.ApiException;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Eviction X-ray queries over the stored safe events. */
@Service
public class EventQueryService {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private final EventRepository events;
    private final SnapshotRepository snapshots;
    private final ApplicationRepository applications;

    public EventQueryService(EventRepository events, SnapshotRepository snapshots, ApplicationRepository applications) {
        this.events = events;
        this.snapshots = snapshots;
        this.applications = applications;
    }

    @Transactional(readOnly = true)
    public List<Event> query(Long applicationId, String region, Integer limit, String actions, String since) {
        ApplicationService app = applications.findById(applicationId).orElseThrow(() -> ApiException.notFound("Application not found"));
        if (region != null && !snapshots.existsByApplicationIdAndCacheRegion(applicationId, region)
                && !events.existsByApplicationIdAndCacheRegion(applicationId, region)) {
            throw ApiException.notFound("Cache region not found");
        }
        int effective = limit == null ? DEFAULT_LIMIT : limit;
        if (effective < 1 || effective > MAX_LIMIT) throw ApiException.badRequest("limit must be between 1 and " + MAX_LIMIT);
        List<String> actionNames = parseActions(actions);
        Instant sinceInstant = parseSince(since);

        Specification<CacheTelemetryEvent> spec = (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(cb.equal(root.get("applicationId"), applicationId));
            if (region != null) ps.add(cb.equal(root.get("cacheRegion"), region));
            if (!actionNames.isEmpty()) ps.add(root.get("action").in(actionNames));
            if (sinceInstant != null) ps.add(cb.greaterThanOrEqualTo(root.<Instant>get("eventTimestamp"), sinceInstant));
            return cb.and(ps.toArray(new Predicate[0]));
        };
        Sort sort = Sort.by(Sort.Order.desc("eventTimestamp"), Sort.Order.desc("id"));
        return events.findAll(spec, PageRequest.of(0, effective, sort)).getContent().stream()
                .map(e -> toDto(e, app)).toList();
    }

    private static List<String> parseActions(String actions) {
        List<String> out = new ArrayList<>();
        if (actions == null || actions.isBlank()) return out;
        for (String token : actions.split(",")) {
            String name = token.trim();
            if (name.isEmpty()) continue;
            try {
                out.add(CacheAction.valueOf(name).name());
            } catch (IllegalArgumentException ex) {
                throw ApiException.badRequest("Unknown action in 'actions' filter");
            }
        }
        return out;
    }

    private static Instant parseSince(String since) {
        if (since == null || since.isBlank()) return null;
        try {
            return Instant.parse(since.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("'since' must be an ISO-8601 instant, e.g. 2026-09-30T09:15:30Z");
        }
    }

    private static Event toDto(CacheTelemetryEvent e, ApplicationService app) {
        return new Event(e.id, e.eventTimestamp, app.name, app.environment, e.cacheRegion, CacheAction.valueOf(e.action),
                e.keyFingerprint, e.reason, EvictionPolicy.valueOf(e.policy), e.frequency, e.lastAccessAgeMs,
                e.remainingTtlMs, e.estimatedEntrySizeBytes, e.cacheSizeBefore, e.cacheSizeAfter, e.memoryBeforeBytes,
                e.memoryAfterBytes, e.valueReturned, EventSeverity.valueOf(e.severity), e.latencyMs);
    }
}

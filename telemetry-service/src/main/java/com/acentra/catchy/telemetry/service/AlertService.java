package com.acentra.catchy.telemetry.service;

import com.acentra.cache.CacheHealthStatus;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Alert;
import com.acentra.catchy.telemetry.service.AggregationService.RegionAggregate;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Derives alerts from the health reasons of WARNING and CRITICAL regions: one alert per reason. The reason text comes
 * verbatim from the SDK's health evaluator; it is only classified (key and severity) here. {@code since} is the first
 * time this service saw the alert active.
 */
@Service
public class AlertService {

    private final Clock clock;
    private final ConcurrentHashMap<String, Instant> firstSeen = new ConcurrentHashMap<>();

    public AlertService(Clock clock) {
        this.clock = clock;
    }

    public List<Alert> alertsFor(ApplicationService app, List<RegionAggregate> regions) {
        Instant now = Times.now(clock);
        List<Alert> out = new ArrayList<>();
        for (RegionAggregate r : regions) {
            CacheHealthStatus status = r.health().status();
            if (status != CacheHealthStatus.WARNING && status != CacheHealthStatus.CRITICAL) continue;
            List<String> reasons = r.health().reasons();
            for (int i = 0; i < reasons.size(); i++) {
                String reason = reasons.get(i);
                Classified c = classify(reason, status, i);
                String id = app.id + ":" + r.cacheRegion() + ":" + c.key();
                Instant since = firstSeen.computeIfAbsent(id, k -> now);
                out.add(new Alert(id, c.severity(), app.id, app.name, r.cacheRegion(), reason, since));
            }
        }
        return out;
    }

    /** Forgets alerts that are no longer active so a recurrence gets a fresh {@code since}. */
    public void retainOnly(Set<String> activeIds) {
        firstSeen.keySet().retainAll(activeIds);
    }

    record Classified(String key, String severity) {}

    static Classified classify(String reason, CacheHealthStatus regionStatus, int index) {
        if (reason.startsWith("Memory utilization")) {
            return new Classified("MEMORY", reason.contains("critical limit") ? "CRITICAL" : "WARNING");
        }
        if (reason.startsWith("Telemetry reporting has failed")) return new Classified("TELEMETRY_FAILURE", "CRITICAL");
        if (reason.startsWith("Telemetry delivery is failing")) return new Classified("TELEMETRY_FAILURE", "WARNING");
        if (reason.contains("stale-data policy violation")) {
            return new Classified("STALE_DATA", reason.contains("must never finalize") ? "CRITICAL" : "WARNING");
        }
        if (reason.startsWith("Source error rate")) {
            return new Classified("SOURCE_ERRORS", reason.contains("(severe)") ? "CRITICAL" : "WARNING");
        }
        if (reason.startsWith("No telemetry received")) {
            return new Classified("TELEMETRY_SILENT", reason.contains("appears to have stopped") ? "CRITICAL" : "WARNING");
        }
        if (reason.startsWith("Hit rate")) return new Classified("HIT_RATE", "WARNING");
        if (reason.contains("evictions occurred")) return new Classified("EVICTIONS", "WARNING");
        if (reason.startsWith("High expiry churn")) return new Classified("EXPIRY_CHURN", "WARNING");
        return new Classified("REASON_" + index, regionStatus == CacheHealthStatus.CRITICAL ? "CRITICAL" : "WARNING");
    }
}

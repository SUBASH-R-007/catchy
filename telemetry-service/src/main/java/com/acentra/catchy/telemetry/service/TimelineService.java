package com.acentra.catchy.telemetry.service;

import static com.acentra.catchy.telemetry.config.Times.round2;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.EventRepository;
import com.acentra.catchy.telemetry.domain.SnapshotPoint;
import com.acentra.catchy.telemetry.domain.SnapshotRepository;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Timeline;
import com.acentra.catchy.telemetry.dto.MetricsDtos.TimelinePoint;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Timelines are per-bucket deltas between consecutive cumulative snapshots of each instance. A counter that goes down
 * means the instance restarted: the new value is then its own baseline (counts since restart), so a delta is never
 * negative. The first snapshot of a series only establishes a baseline. Empty buckets are included.
 */
@Service
public class TimelineService {
    private static final Duration BASELINE_MARGIN = Duration.ofMinutes(5);

    private final SnapshotRepository snapshots;
    private final EventRepository events;
    private final ApplicationRepository applications;
    private final Clock clock;

    public TimelineService(SnapshotRepository snapshots, EventRepository events, ApplicationRepository applications, Clock clock) {
        this.snapshots = snapshots;
        this.events = events;
        this.applications = applications;
        this.clock = clock;
    }

    /** @param applicationId null for the global timeline; @param region null for app-level / global. */
    @Transactional(readOnly = true)
    public Timeline timeline(Long applicationId, String region, int minutes, int bucketSeconds) {
        if (minutes < 1 || minutes > 120) throw ApiException.badRequest("minutes must be between 1 and 120");
        if (bucketSeconds < 5 || bucketSeconds > 300) throw ApiException.badRequest("bucketSeconds must be between 5 and 300");
        if (applicationId != null) {
            if (!applications.existsById(applicationId)) throw ApiException.notFound("Application not found");
            if (region != null && !snapshots.existsByApplicationIdAndCacheRegion(applicationId, region)
                    && !events.existsByApplicationIdAndCacheRegion(applicationId, region)) {
                throw ApiException.notFound("Cache region not found");
            }
        }
        Instant now = Times.now(clock);
        long b = bucketSeconds;
        long endBucket = Math.floorDiv(now.getEpochSecond(), b);
        int buckets = (int) (minutes * 60L / b);
        long startBucket = endBucket - buckets;
        Instant windowStart = Instant.ofEpochSecond(startBucket * b);
        Instant from = windowStart.minus(BASELINE_MARGIN);
        Instant to = now.plusSeconds(b);

        List<SnapshotPoint> points = applicationId == null ? snapshots.pointsGlobal(from, to)
                : region == null ? snapshots.pointsForApplication(applicationId, from, to)
                : snapshots.pointsForRegion(applicationId, region, from, to);

        long[] hits = new long[buckets + 1], misses = new long[buckets + 1], puts = new long[buckets + 1],
                evictions = new long[buckets + 1], expirations = new long[buckets + 1];
        Map<String, SnapshotPoint> previous = new LinkedHashMap<>();
        for (SnapshotPoint p : points) {
            String series = p.applicationId() + "|" + p.cacheRegion() + "|" + p.instanceId();
            SnapshotPoint prev = previous.put(series, p);
            if (prev == null) continue; // baseline only
            boolean restart = p.hits() < prev.hits() || p.misses() < prev.misses() || p.puts() < prev.puts()
                    || p.evictions() < prev.evictions() || p.expirations() < prev.expirations();
            long bucket = Math.floorDiv(p.capturedAt().getEpochSecond(), b);
            if (bucket < startBucket) continue;
            int idx = (int) (Math.min(bucket, endBucket) - startBucket);
            hits[idx] += restart ? p.hits() : p.hits() - prev.hits();
            misses[idx] += restart ? p.misses() : p.misses() - prev.misses();
            puts[idx] += restart ? p.puts() : p.puts() - prev.puts();
            evictions[idx] += restart ? p.evictions() : p.evictions() - prev.evictions();
            expirations[idx] += restart ? p.expirations() : p.expirations() - prev.expirations();
        }

        List<TimelinePoint> out = new ArrayList<>(buckets + 1);
        for (int i = 0; i <= buckets; i++) {
            long total = hits[i] + misses[i];
            double rate = total == 0 ? 0.0 : round2((double) hits[i] / total * 100.0);
            out.add(new TimelinePoint(Instant.ofEpochSecond((startBucket + i) * b), hits[i], misses[i], puts[i],
                    evictions[i], expirations[i], rate));
        }
        return new Timeline(region, bucketSeconds, out);
    }
}

package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Validates telemetry payloads per docs/api.md. Messages name fields and rules only, never echo submitted values.
 */
public final class TelemetryValidator {
    static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");
    static final Pattern FINGERPRINT = Pattern.compile("^sha256:[0-9a-f]{8,64}$");
    static final Pattern INSTANCE = Pattern.compile("^[A-Za-z0-9._-]{1,80}$");
    static final int MAX_REASON = 400;

    private TelemetryValidator() {}

    public static List<String> validateBatch(TelemetryBatch b, CatchyProperties.Ingest limits) {
        List<String> errors = new ArrayList<>();
        if (b.applicationName() != null && b.applicationName().length() > 63) errors.add("applicationName: must be at most 63 characters");
        if (b.environment() != null && b.environment().length() > 31) errors.add("environment: must be at most 31 characters");
        if (b.instanceId() != null && !INSTANCE.matcher(b.instanceId()).matches()) {
            errors.add("instanceId: must match " + INSTANCE.pattern());
        }
        if (b.sdkVersion() != null && b.sdkVersion().length() > 40) errors.add("sdkVersion: must be at most 40 characters");
        List<TelemetryEvent> events = b.events() == null ? List.of() : b.events();
        List<RegionSnapshot> snapshots = b.snapshots() == null ? List.of() : b.snapshots();
        if (events.size() > limits.maxBatchEvents()) {
            errors.add("events: must contain at most " + limits.maxBatchEvents() + " items");
        } else {
            for (int i = 0; i < events.size(); i++) {
                TelemetryEvent e = events.get(i);
                if (e == null) {
                    errors.add("events[" + i + "]: must not be null");
                } else {
                    validateEvent(e, "events[" + i + "].", errors);
                }
            }
        }
        if (snapshots.size() > limits.maxSnapshotsPerBatch()) {
            errors.add("snapshots: must contain at most " + limits.maxSnapshotsPerBatch() + " items");
        } else {
            for (int i = 0; i < snapshots.size(); i++) {
                RegionSnapshot s = snapshots.get(i);
                if (s == null) {
                    errors.add("snapshots[" + i + "]: must not be null");
                } else {
                    validateSnapshot(s, "snapshots[" + i + "].", errors);
                }
            }
        }
        return errors;
    }

    /** The single-event endpoint additionally requires applicationName and environment. */
    public static List<String> validateSingleEvent(TelemetryEvent e) {
        List<String> errors = new ArrayList<>();
        if (e.applicationName() == null || e.applicationName().isBlank()) errors.add("applicationName: must not be blank");
        if (e.environment() == null || e.environment().isBlank()) errors.add("environment: must not be blank");
        validateEvent(e, "", errors);
        return errors;
    }

    static void validateEvent(TelemetryEvent e, String p, List<String> errors) {
        if (e.timestamp() == null) errors.add(p + "timestamp: must not be null");
        if (e.cacheRegion() == null || !REGION.matcher(e.cacheRegion()).matches()) {
            errors.add(p + "cacheRegion: must match " + REGION.pattern());
        }
        if (e.action() == null) errors.add(p + "action: must not be null");
        if (e.policy() == null) errors.add(p + "policy: must not be null");
        if (e.keyFingerprint() != null && !FINGERPRINT.matcher(e.keyFingerprint()).matches()) {
            errors.add(p + "keyFingerprint: must match " + FINGERPRINT.pattern() + " or be null");
        }
        if (e.reason() != null && e.reason().length() > MAX_REASON) {
            errors.add(p + "reason: must be at most " + MAX_REASON + " characters");
        }
        if (e.applicationName() != null && e.applicationName().length() > 63) errors.add(p + "applicationName: must be at most 63 characters");
        if (e.environment() != null && e.environment().length() > 31) errors.add(p + "environment: must be at most 31 characters");
        nonNegative(errors, p + "frequency", e.frequency());
        nonNegative(errors, p + "lastAccessAgeMs", e.lastAccessAgeMs());
        nonNegative(errors, p + "remainingTtlMs", e.remainingTtlMs());
        nonNegative(errors, p + "estimatedEntrySizeBytes", e.estimatedEntrySizeBytes());
        nonNegative(errors, p + "cacheSizeBefore", e.cacheSizeBefore());
        nonNegative(errors, p + "cacheSizeAfter", e.cacheSizeAfter());
        nonNegative(errors, p + "memoryBeforeBytes", e.memoryBeforeBytes());
        nonNegative(errors, p + "memoryAfterBytes", e.memoryAfterBytes());
        nonNegative(errors, p + "latencyMs", e.latencyMs());
    }

    static void validateSnapshot(RegionSnapshot s, String p, List<String> errors) {
        if (s.capturedAt() == null) errors.add(p + "capturedAt: must not be null");
        if (s.cacheRegion() == null || !REGION.matcher(s.cacheRegion()).matches()) {
            errors.add(p + "cacheRegion: must match " + REGION.pattern());
        }
        if (s.riskLevel() == null) errors.add(p + "riskLevel: must not be null");
        if (s.activePolicy() == null) errors.add(p + "activePolicy: must not be null");
        long[] counters = {s.hits(), s.misses(), s.puts(), s.removes(), s.clears(), s.evictions(), s.expirations(),
                s.size(), s.capacity(), s.estimatedMemoryUsageBytes(), s.maximumMemoryBytes(), s.lruEvictions(),
                s.lfuEvictions(), s.evictionsDueToEntryLimit(), s.evictionsDueToMemoryLimit(), s.sourceCallsAvoided(),
                s.telemetryEventsSent(), s.telemetryEventsFailed(), s.telemetryFailureStreak(), s.l1Hits(), s.victimHits(),
                s.sourceMisses(), s.victimEvictions(), s.victimSize(), s.victimCapacity(), s.refreshesStarted(),
                s.concurrentRequestsCoalesced(), s.sourceCallsAvoidedByStampedeShield(), s.refreshFailures(),
                s.sourceCalls(), s.sourceErrors(), s.staleServed(), s.staleCorrections(), s.staleDataViolations(),
                s.defaultTtlMs()};
        for (long c : counters) {
            if (c < 0) {
                errors.add(p + "counters: must not be negative");
                break;
            }
        }
        nonNegative(errors, p + "averageGetLatencyMs", s.averageGetLatencyMs());
        nonNegative(errors, p + "averagePutLatencyMs", s.averagePutLatencyMs());
        RegionSnapshot.RecentWindow rw = s.recentWindow();
        if (rw != null && (rw.minutes() < 0 || rw.hits() < 0 || rw.misses() < 0 || rw.puts() < 0 || rw.evictions() < 0
                || rw.expirations() < 0)) {
            errors.add(p + "recentWindow: values must not be negative");
        }
        RegionSnapshot.Shadow sh = s.shadow();
        if (sh != null) {
            if (sh.requests() < 0 || sh.windowRequests() < 0) errors.add(p + "shadow: request counts must not be negative");
            percent(errors, p + "shadow.lruHitRate", sh.lruHitRate());
            percent(errors, p + "shadow.lfuHitRate", sh.lfuHitRate());
            percent(errors, p + "shadow.topKeyConcentrationPercent", sh.topKeyConcentrationPercent());
        }
    }

    private static void nonNegative(List<String> errors, String field, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) errors.add(field + ": must be a number >= 0");
    }

    private static void percent(List<String> errors, String field, double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0 || v > 100) errors.add(field + ": must be between 0 and 100");
    }
}

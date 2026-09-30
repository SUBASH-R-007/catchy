package com.acentra.cache;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic health rules shared by the SDK and the telemetry service.
 * <pre>
 * CRITICAL  memory >= 98%, telemetry failing (3+ consecutive), stale-data violation in HIGH/CRITICAL region,
 *           severe source error rate (>= 25%), or no telemetry for 120 s+
 * WARNING   hit rate &lt; 65%, memory >= 90%, heavy evictions, high expiry churn, telemetry failing, or silent 30 s+
 * EXCELLENT hit rate >= 85% and memory &lt; 80% (and nothing above)
 * GOOD      everything else with enough data; UNKNOWN when fewer than 20 requests
 * </pre>
 */
public final class CacheHealthEvaluator {
    public static final long MIN_REQUESTS = 20;
    public static final double HIT_RATE_TARGET = 65.0;
    public static final double HIT_RATE_EXCELLENT = 85.0;
    public static final double MEMORY_WARNING = 90.0;
    public static final double MEMORY_EXCELLENT = 80.0;
    public static final double MEMORY_CRITICAL = 98.0;

    private CacheHealthEvaluator() {}

    /** @param secondsSinceLastTelemetry null when evaluated inside the SDK itself. */
    public record Input(
            long requests,
            double hitRatePercent,
            double memoryUtilizationPercent,
            long evictionsRecent,
            long expirationsRecent,
            long putsRecent,
            int recentWindowMinutes,
            int telemetryFailureStreak,
            CacheRiskLevel riskLevel,
            long staleDataViolations,
            double sourceErrorRatePercent,
            Long secondsSinceLastTelemetry) {}

    public static CacheHealthReport evaluate(Input in) {
        List<String> critical = new ArrayList<>();
        List<String> warning = new ArrayList<>();
        List<String> ok = new ArrayList<>();
        boolean enough = in.requests() >= MIN_REQUESTS;

        if (in.memoryUtilizationPercent() >= MEMORY_CRITICAL) {
            critical.add("Memory utilization is " + Humanize.percent(in.memoryUtilizationPercent())
                    + ", at or above the " + (int) MEMORY_CRITICAL + "% critical limit.");
        } else if (in.memoryUtilizationPercent() >= MEMORY_WARNING) {
            warning.add("Memory utilization is " + Humanize.percent(in.memoryUtilizationPercent()) + ".");
        } else {
            ok.add("Memory utilization is " + Humanize.percent(in.memoryUtilizationPercent()) + ", below "
                    + (int) (in.memoryUtilizationPercent() < MEMORY_EXCELLENT ? MEMORY_EXCELLENT : MEMORY_WARNING) + "%.");
        }
        if (in.telemetryFailureStreak() >= 3) {
            critical.add("Telemetry reporting has failed " + in.telemetryFailureStreak() + " times in a row.");
        } else if (in.telemetryFailureStreak() >= 1) {
            warning.add("Telemetry delivery is failing (" + in.telemetryFailureStreak() + " consecutive failure"
                    + (in.telemetryFailureStreak() > 1 ? "s" : "") + ").");
        }
        if (in.staleDataViolations() > 0) {
            String msg = in.staleDataViolations() + " stale-data policy violation(s) recorded.";
            if (in.riskLevel() != null && in.riskLevel().isElevated()) {
                critical.add(msg + " Region risk is " + in.riskLevel() + "; stale data must never finalize a decision.");
            } else {
                warning.add(msg);
            }
        }
        if (in.sourceErrorRatePercent() >= 25.0) {
            critical.add("Source error rate is " + Humanize.percent(in.sourceErrorRatePercent()) + " (severe).");
        } else if (in.sourceErrorRatePercent() >= 5.0) {
            warning.add("Source error rate is " + Humanize.percent(in.sourceErrorRatePercent()) + ".");
        }
        Long silent = in.secondsSinceLastTelemetry();
        if (silent != null) {
            if (silent >= 120) {
                critical.add("No telemetry received for " + silent + " seconds; reporting appears to have stopped.");
            } else if (silent >= 30) {
                warning.add("No telemetry received for " + silent + " seconds.");
            }
        }
        if (enough) {
            if (in.hitRatePercent() < HIT_RATE_TARGET) {
                warning.add("Hit rate is " + Humanize.percent(in.hitRatePercent()) + ", below " + (int) HIT_RATE_TARGET + "% target.");
            } else {
                ok.add("Hit rate is " + Humanize.percent(in.hitRatePercent()) + ", above "
                        + (int) (in.hitRatePercent() >= HIT_RATE_EXCELLENT ? HIT_RATE_EXCELLENT : HIT_RATE_TARGET) + "% target.");
            }
        }
        boolean heavyEvictions = in.evictionsRecent() >= 100
                || (in.putsRecent() >= 20 && in.evictionsRecent() >= 0.5 * in.putsRecent() && in.evictionsRecent() >= 10);
        if (heavyEvictions) {
            warning.add(in.evictionsRecent() + " evictions occurred in the last " + in.recentWindowMinutes() + " minutes.");
        }
        boolean expiryChurn = in.expirationsRecent() >= 100
                || (in.putsRecent() >= 20 && in.expirationsRecent() >= 0.8 * in.putsRecent() && in.expirationsRecent() >= 10);
        if (expiryChurn) {
            warning.add("High expiry churn: " + in.expirationsRecent() + " entries expired in the last "
                    + in.recentWindowMinutes() + " minutes; TTL may be too short.");
        }

        CacheHealthStatus status;
        List<String> reasons = new ArrayList<>();
        if (!critical.isEmpty()) {
            status = CacheHealthStatus.CRITICAL;
            reasons.addAll(critical);
            reasons.addAll(warning);
        } else if (!warning.isEmpty()) {
            status = CacheHealthStatus.WARNING;
            reasons.addAll(warning);
        } else if (!enough) {
            status = CacheHealthStatus.UNKNOWN;
            reasons.add("Not enough requests yet (" + in.requests() + " of " + MIN_REQUESTS + ") to score cache health.");
        } else if (in.hitRatePercent() >= HIT_RATE_EXCELLENT && in.memoryUtilizationPercent() < MEMORY_EXCELLENT) {
            status = CacheHealthStatus.EXCELLENT;
            reasons.addAll(ok);
        } else {
            status = CacheHealthStatus.GOOD;
            reasons.addAll(ok);
        }
        return new CacheHealthReport(status, score(status, in), List.copyOf(reasons));
    }

    private static int score(CacheHealthStatus status, Input in) {
        if (status == CacheHealthStatus.UNKNOWN) return 0;
        double evictionRatio = ratio(in.evictionsRecent(), in.putsRecent());
        double expiryRatio = ratio(in.expirationsRecent(), in.putsRecent());
        double raw = in.hitRatePercent() * 0.55
                + (100 - Math.min(100, in.memoryUtilizationPercent())) * 0.20
                + 100 * (1 - evictionRatio) * 0.15
                + 100 * (1 - expiryRatio) * 0.10;
        int lo;
        int hi;
        switch (status) {
            case EXCELLENT -> { lo = 85; hi = 100; }
            case GOOD -> { lo = 65; hi = 84; }
            case WARNING -> { lo = 35; hi = 64; }
            default -> { lo = 0; hi = 34; }
        }
        return (int) Math.max(lo, Math.min(hi, Math.round(raw)));
    }

    private static double ratio(long part, long whole) {
        if (whole <= 0) return part > 0 ? 1.0 : 0.0;
        return Math.min(1.0, (double) part / whole);
    }

    @Override
    public String toString() {
        return String.format(Locale.ROOT, "CacheHealthEvaluator[minRequests=%d]", MIN_REQUESTS);
    }
}

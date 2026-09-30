package com.acentra.catchy.telemetry.dto;

import static com.acentra.catchy.telemetry.config.Times.round2;
import static com.acentra.catchy.telemetry.config.Times.round4;

import com.acentra.catchy.telemetry.domain.CacheMetricsSnapshot;
import java.util.Collection;

/**
 * Flat counters shared by region, application, project and global views (docs/api.md "MetricTotals").
 * Totals are sums across instances; rates are derived from the summed counters and latencies are weighted by
 * operation counts (gets = hits + misses, puts = puts).
 */
public record MetricTotals(
        long hits, long misses, double hitRate, double missRate,
        long puts, long removes, long clears, long evictions, long expirations,
        long size, long capacity,
        long estimatedMemoryUsageBytes, long maximumMemoryBytes, double memoryUtilizationPercent,
        long lruEvictions, long lfuEvictions,
        long evictionsDueToEntryLimit, long evictionsDueToMemoryLimit,
        double averageGetLatencyMs, double averagePutLatencyMs,
        long sourceCallsAvoided,
        long telemetryEventsSent, long telemetryEventsFailed,
        long l1Hits, long victimHits, long sourceMisses, long victimEvictions, double overallHitRate,
        long refreshesStarted, long concurrentRequestsCoalesced,
        long sourceCallsAvoidedByStampedeShield, long refreshFailures,
        long staleServed, long staleCorrections) {

    public static MetricTotals empty() {
        return new Acc().build();
    }

    public static MetricTotals sum(Collection<MetricTotals> parts) {
        Acc acc = new Acc();
        parts.forEach(acc::add);
        return acc.build();
    }

    public static MetricTotals of(CacheMetricsSnapshot s) {
        return new Acc().addSnapshot(s).build();
    }

    public long requests() {
        return hits + misses;
    }

    /** Mutable accumulator; the single place where derived values are computed. */
    public static final class Acc {
        long hits, misses, puts, removes, clears, evictions, expirations, size, capacity;
        long mem, maxMem, lru, lfu, entryLimit, memLimit, avoided, sent, failed;
        long l1, victimHits, sourceMisses, victimEvictions, refreshes, coalesced, stampede, refreshFailures;
        long staleServed, staleCorrections;
        double getWeighted, putWeighted;

        public Acc add(MetricTotals t) {
            hits += t.hits;
            misses += t.misses;
            puts += t.puts;
            removes += t.removes;
            clears += t.clears;
            evictions += t.evictions;
            expirations += t.expirations;
            size += t.size;
            capacity += t.capacity;
            mem += t.estimatedMemoryUsageBytes;
            maxMem += t.maximumMemoryBytes;
            lru += t.lruEvictions;
            lfu += t.lfuEvictions;
            entryLimit += t.evictionsDueToEntryLimit;
            memLimit += t.evictionsDueToMemoryLimit;
            avoided += t.sourceCallsAvoided;
            sent += t.telemetryEventsSent;
            failed += t.telemetryEventsFailed;
            l1 += t.l1Hits;
            victimHits += t.victimHits;
            sourceMisses += t.sourceMisses;
            victimEvictions += t.victimEvictions;
            refreshes += t.refreshesStarted;
            coalesced += t.concurrentRequestsCoalesced;
            stampede += t.sourceCallsAvoidedByStampedeShield;
            refreshFailures += t.refreshFailures;
            staleServed += t.staleServed;
            staleCorrections += t.staleCorrections;
            getWeighted += t.averageGetLatencyMs * (t.hits + t.misses);
            putWeighted += t.averagePutLatencyMs * t.puts;
            return this;
        }

        public Acc addSnapshot(CacheMetricsSnapshot s) {
            hits += s.hits;
            misses += s.misses;
            puts += s.puts;
            removes += s.removes;
            clears += s.clears;
            evictions += s.evictions;
            expirations += s.expirations;
            size += s.cacheSize;
            capacity += s.capacity;
            mem += s.estimatedMemoryUsageBytes;
            maxMem += s.maximumMemoryBytes;
            lru += s.lruEvictions;
            lfu += s.lfuEvictions;
            entryLimit += s.evictionsDueToEntryLimit;
            memLimit += s.evictionsDueToMemoryLimit;
            avoided += s.sourceCallsAvoided;
            sent += s.telemetryEventsSent;
            failed += s.telemetryEventsFailed;
            l1 += s.l1Hits;
            victimHits += s.victimHits;
            sourceMisses += s.sourceMisses;
            victimEvictions += s.victimEvictions;
            refreshes += s.refreshesStarted;
            coalesced += s.concurrentRequestsCoalesced;
            stampede += s.sourceCallsAvoidedByStampedeShield;
            refreshFailures += s.refreshFailures;
            staleServed += s.staleServed;
            staleCorrections += s.staleCorrections;
            getWeighted += s.averageGetLatencyMs * (s.hits + s.misses);
            putWeighted += s.averagePutLatencyMs * s.puts;
            return this;
        }

        /** Exact (unrounded) hit rate in percent, used as input for the health evaluator. */
        public double exactHitRate() {
            long req = hits + misses;
            return req == 0 ? 0.0 : (double) hits / req * 100.0;
        }

        /** Exact (unrounded) memory utilization in percent. */
        public double exactMemoryUtilization() {
            return maxMem <= 0 ? 0.0 : (double) mem / maxMem * 100.0;
        }

        public long requests() {
            return hits + misses;
        }

        public MetricTotals build() {
            long req = hits + misses;
            double hitRate = req == 0 ? 0.0 : round2((double) hits / req * 100.0);
            double missRate = req == 0 ? 0.0 : round2(100.0 - hitRate);
            double util = maxMem <= 0 ? 0.0 : round2((double) mem / maxMem * 100.0);
            double overall = req == 0 ? 0.0 : round2((double) (l1 + victimHits) / req * 100.0);
            double avgGet = req == 0 ? 0.0 : round4(getWeighted / req);
            double avgPut = puts == 0 ? 0.0 : round4(putWeighted / puts);
            return new MetricTotals(hits, misses, hitRate, missRate, puts, removes, clears, evictions, expirations,
                    size, capacity, mem, maxMem, util, lru, lfu, entryLimit, memLimit, avgGet, avgPut, avoided, sent,
                    failed, l1, victimHits, sourceMisses, victimEvictions, overall, refreshes, coalesced, stampede,
                    refreshFailures, staleServed, staleCorrections);
        }
    }
}

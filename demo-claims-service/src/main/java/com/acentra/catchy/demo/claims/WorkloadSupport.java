package com.acentra.catchy.demo.claims;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Shared plumbing of the synthetic workloads: bounded parallel execution and before/after metric deltas. */
final class WorkloadSupport {
    private static final Logger LOG = LoggerFactory.getLogger(WorkloadSupport.class);

    static final int WORKERS = 16;
    /** Workloads are synchronous; stop issuing requests after this long and say so in the summary. */
    static final long MAX_RUN_NANOS = 10_000_000_000L;

    private WorkloadSupport() {}

    record RegionDelta(String region, String activePolicy, String riskLevel, long hits, long misses, long evictions,
                       long entryLimitEvictions, long memoryLimitEvictions, long expirations, long sourceCalls,
                       long sourceCallsAvoided, long requestsCoalesced, int size, int capacity) {}

    record WorkloadSummary(String workload, String description, int requestedRequests, int requests, int failedRequests,
                           boolean truncated, long durationMs, long cacheHits, long cacheMisses, double hitRatePercent,
                           long sourceCallsMade, long sourceCallsAvoided, long evictions, long expirations,
                           List<RegionDelta> regions, String hint) {}

    /** Point-in-time counters of a set of regions, used to compute what a workload changed. */
    static final class Snapshot {
        private final Map<String, CacheMetrics> metrics = new LinkedHashMap<>();

        static Snapshot of(List<AcentraCache<?, ?>> caches) {
            Snapshot s = new Snapshot();
            for (AcentraCache<?, ?> c : caches) s.metrics.put(c.getConfig().regionName(), c.getMetrics());
            return s;
        }

        List<RegionDelta> deltaTo(Snapshot after, List<AcentraCache<?, ?>> caches) {
            List<RegionDelta> out = new ArrayList<>();
            for (AcentraCache<?, ?> c : caches) {
                String name = c.getConfig().regionName();
                CacheMetrics b = metrics.get(name);
                CacheMetrics a = after.metrics.get(name);
                out.add(new RegionDelta(name, a.activePolicy().name(), c.getConfig().riskLevel().name(),
                        a.hits() - b.hits(), a.misses() - b.misses(), a.evictions() - b.evictions(),
                        a.evictionsDueToEntryLimit() - b.evictionsDueToEntryLimit(),
                        a.evictionsDueToMemoryLimit() - b.evictionsDueToMemoryLimit(), a.expirations() - b.expirations(),
                        a.sourceCalls() - b.sourceCalls(), a.sourceCallsAvoided() - b.sourceCallsAvoided(),
                        a.concurrentRequestsCoalesced() - b.concurrentRequestsCoalesced(), a.size(), a.capacity()));
            }
            return out;
        }
    }

    static WorkloadSummary summarize(String workload, String description, int requested, Outcome outcome, long startNanos,
                                     List<RegionDelta> regions, String hint) {
        long hits = regions.stream().mapToLong(RegionDelta::hits).sum();
        long misses = regions.stream().mapToLong(RegionDelta::misses).sum();
        double rate = hits + misses == 0 ? 0 : Math.round(hits * 10000.0 / (hits + misses)) / 100.0;
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        LOG.info("Workload '{}' finished: requests={} failed={} hits={} misses={} durationMs={}", workload, outcome.executed(),
                outcome.failed(), hits, misses, ms);
        return new WorkloadSummary(workload, description, requested, outcome.executed(), outcome.failed(), outcome.truncated(),
                ms, hits, misses, rate, regions.stream().mapToLong(RegionDelta::sourceCalls).sum(),
                regions.stream().mapToLong(RegionDelta::sourceCallsAvoided).sum(),
                regions.stream().mapToLong(RegionDelta::evictions).sum(),
                regions.stream().mapToLong(RegionDelta::expirations).sum(), regions, hint);
    }

    record Outcome(int executed, int failed, boolean truncated) {
        Outcome plus(Outcome o) {
            return new Outcome(executed + o.executed, failed + o.failed, truncated || o.truncated);
        }
    }

    /**
     * Runs the tasks on up to {@link #WORKERS} threads, taking them in order from a shared index so the access pattern is
     * preserved approximately. Individual failures are counted, never logged with details.
     */
    static Outcome run(List<Runnable> tasks, long deadlineNanos) {
        // Small runs stay (nearly) sequential so the access pattern, and thus the hit rate, is not distorted by many
        // threads missing the same cold key at once; big runs scale up to WORKERS to stay within the time budget.
        return run(tasks, deadlineNanos, Math.max(1, Math.min(WORKERS, tasks.size() / 75)));
    }

    /** Same as {@link #run(List, long)} with an explicit number of concurrent workers (at most 64). */
    static Outcome run(List<Runnable> tasks, long deadlineNanos, int requestedWorkers) {
        if (tasks.isEmpty()) return new Outcome(0, 0, false);
        AtomicInteger next = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        int workers = Math.max(1, Math.min(Math.min(64, requestedWorkers), tasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "demo-workload");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                futures.add(pool.submit(() -> {
                    int i;
                    while (System.nanoTime() - deadlineNanos < 0 && (i = next.getAndIncrement()) < tasks.size()) {
                        try {
                            tasks.get(i).run();
                            done.incrementAndGet();
                        } catch (RuntimeException e) {
                            failed.incrementAndGet();
                        }
                    }
                }));
            }
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return new Outcome(done.get(), failed.get(), done.get() + failed.get() < tasks.size());
    }

    static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package com.acentra.catchy.demo.claims;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupSummary;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSet;
import com.acentra.catchy.demo.claims.WorkloadSupport.Outcome;
import com.acentra.catchy.demo.claims.WorkloadSupport.RegionDelta;
import com.acentra.catchy.demo.claims.WorkloadSupport.Snapshot;
import com.acentra.catchy.demo.claims.WorkloadSupport.WorkloadSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Synthetic request patterns that make the cache behave visibly differently. All ids are synthetic rule-set / provider-group
 * ids (no patient data). Each run is synchronous and reports what it changed, computed from SDK metric deltas.
 */
@Service
public class WorkloadService {

    public static final int DEFAULT_REQUESTS = 300;
    public static final int DEFAULT_EXPIRE_REQUESTS = 60;
    public static final int DEFAULT_STAMPEDE_CALLERS = 24;

    private static final int POPULAR_PHASE = 80;
    private static final int SCAN_BURST = 45;
    private static final int HOT_RULES = 24;
    private static final int WARM_RULES = 12;
    private static final int SHIFT_WINDOW = 30;
    private static final int PROVIDER_GROUPS = 60;
    private static final Duration SHORT_TTL = Duration.ofSeconds(2);

    private final ClaimsCacheService service;
    private final AcentraCacheManager manager;
    private final DemoProperties props;
    private final List<AcentraCache<?, ?>> regions;

    private final AtomicLong scanCounter = new AtomicLong();
    private final AtomicLong shiftCounter = new AtomicLong();
    private final AtomicLong runCounter = new AtomicLong();

    public WorkloadService(ClaimsCacheService service, AcentraCacheManager manager, DemoProperties props,
                           AcentraCache<String, RuleSet> claimRulesCache,
                           AcentraCache<String, ProviderGroupSummary> providerDirectoryCache) {
        this.service = service;
        this.manager = manager;
        this.props = props;
        this.regions = List.of(claimRulesCache, providerDirectoryCache);
    }

    public List<AcentraCache<?, ?>> regions() {
        return regions;
    }

    /** Stable popularity: a few hot rule sets dominate, interrupted by cold scans. Favours LFU. */
    public WorkloadSummary repeated(int requests) {
        Random rnd = new Random(1_000 + runCounter.incrementAndGet());
        List<Runnable> tasks = new ArrayList<>(requests);
        int ruleIdx = 0;
        for (int i = 0; i < requests; i++) {
            if (i % 5 == 4) {
                String pg = popularProvider(rnd);
                tasks.add(() -> service.provider(pg));
            } else {
                int pos = ruleIdx++ % (POPULAR_PHASE + SCAN_BURST);
                String id = pos < POPULAR_PHASE ? popularRule(rnd) : "RULESET-SCAN-" + scanCounter.incrementAndGet();
                tasks.add(() -> service.ruleSet(id));
            }
        }
        return execute("repeated",
                "Stable popularity: a few hot rule sets dominate, with periodic cold scans larger than the cache. Frequency-based "
                        + "eviction (LFU) keeps the hot sets; LRU loses them to every scan.", requests, tasks);
    }

    /** Shifting working set: the set of popular keys slides over time. Favours LRU. */
    public WorkloadSummary changing(int requests) {
        Random rnd = new Random(2_000 + runCounter.incrementAndGet());
        long start = shiftCounter.getAndAdd(requests / 4 + 1L);
        List<Runnable> tasks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            if (i % 5 == 4) {
                String pg = popularProvider(rnd);
                tasks.add(() -> service.provider(pg));
            } else {
                String id = String.format("RULESET-SHIFT-%05d", start + i / 4 + rnd.nextInt(SHIFT_WINDOW));
                tasks.add(() -> service.ruleSet(id));
            }
        }
        return execute("changing",
                "Shifting working set: the popular rule sets change continuously. Recency-based eviction (LRU) follows the shift; "
                        + "LFU keeps stale favourites and evicts the newcomers.", requests, tasks);
    }

    /**
     * Inserts short-TTL (2 s) entries in both regions, reads them while fresh (HIT), waits for them to expire and reads
     * again (MISS + EXPIRED, reloaded from the source; the LOW-risk provider region may serve stale while revalidating).
     */
    public WorkloadSummary expire(int requests) {
        int kRules = clamp(requests / 6, 3, 15);
        int kProviders = clamp(requests / 6, 3, 20);
        String run = Long.toString(runCounter.incrementAndGet(), 36);
        List<Runnable> load = new ArrayList<>();
        List<Runnable> readNow = new ArrayList<>();
        List<Runnable> readAfter = new ArrayList<>();
        for (int i = 0; i < kRules; i++) {
            String id = "RULESET-EXP-" + run + "-" + i;
            load.add(() -> service.ruleSet(id, SHORT_TTL));
            readNow.add(() -> service.ruleSet(id));
            readAfter.add(() -> service.ruleSet(id));
        }
        for (int i = 0; i < kProviders; i++) {
            String id = "PG-EXP-" + run + "-" + i;
            load.add(() -> service.provider(id, SHORT_TTL));
            readNow.add(() -> service.provider(id));
            readAfter.add(() -> service.provider(id));
        }
        Snapshot before = Snapshot.of(regions);
        long t0 = System.nanoTime();
        long deadline = t0 + WorkloadSupport.MAX_RUN_NANOS;
        Outcome out = WorkloadSupport.run(load, deadline).plus(WorkloadSupport.run(readNow, deadline));
        WorkloadSupport.sleepQuietly(SHORT_TTL.toMillis() + 100);
        out = out.plus(WorkloadSupport.run(readAfter, deadline));
        WorkloadSupport.sleepQuietly(150); // let background stale-while-revalidate refreshes finish so counters are complete
        List<RegionDelta> deltas = before.deltaTo(Snapshot.of(regions), regions);
        return WorkloadSupport.summarize("expire",
                "Short-TTL (2 s) entries are loaded, read while fresh, then read again after they expired: MISS + EXPIRED, reloaded "
                        + "from the source.", requests, out, t0, deltas, hint());
    }

    /** Many concurrent callers ask for the same uncached rule set: the stampede shield makes ONE source call. */
    public WorkloadSummary stampede(int callers) {
        int n = clamp(callers, 10, 64);
        String id = "RULESET-STAMPEDE-" + Long.toString(runCounter.incrementAndGet(), 36);
        List<Runnable> tasks = new ArrayList<>(n);
        for (int i = 0; i < n; i++) tasks.add(() -> service.ruleSet(id));
        return execute("stampede",
                "Concurrent requests for the same uncached rule set share one source call (single-flight stampede shield).",
                callers, tasks, n);
    }

    private WorkloadSummary execute(String name, String description, int requested, List<Runnable> tasks) {
        return execute(name, description, requested, tasks, 0);
    }

    /** @param workers concurrent callers, or 0 for the automatic choice. */
    private WorkloadSummary execute(String name, String description, int requested, List<Runnable> tasks, int workers) {
        Snapshot before = Snapshot.of(regions);
        long t0 = System.nanoTime();
        long deadline = t0 + WorkloadSupport.MAX_RUN_NANOS;
        Outcome out = workers > 0 ? WorkloadSupport.run(tasks, deadline, workers) : WorkloadSupport.run(tasks, deadline);
        List<RegionDelta> deltas = before.deltaTo(Snapshot.of(regions), regions);
        return WorkloadSupport.summarize(name, description, requested, out, t0, deltas, hint());
    }

    private String hint() {
        boolean telemetryOn = manager.telemetry() != TelemetryClient.noop();
        return "Open the dashboard at " + props.getDashboardUrl() + " to watch these counters"
                + (telemetryOn ? " update live (claims-service)."
                : " (telemetry is disabled here: set CATCHY_CLAIMS_API_KEY to send safe telemetry to the telemetry service).");
    }

    private static String popularRule(Random rnd) {
        if (rnd.nextDouble() < 0.85) return String.format("RULESET-HOT-%02d", (int) (HOT_RULES * Math.pow(rnd.nextDouble(), 2.0)));
        return String.format("RULESET-WARM-%02d", rnd.nextInt(WARM_RULES));
    }

    private static String popularProvider(Random rnd) {
        return String.format("PG-%03d", (int) (PROVIDER_GROUPS * Math.pow(rnd.nextDouble(), 2.0)));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

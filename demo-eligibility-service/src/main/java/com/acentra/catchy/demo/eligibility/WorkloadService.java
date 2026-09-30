package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.catchy.demo.eligibility.EligibilityModels.AuthorizationDecision;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResult;
import com.acentra.catchy.demo.eligibility.WorkloadSupport.Outcome;
import com.acentra.catchy.demo.eligibility.WorkloadSupport.RegionDelta;
import com.acentra.catchy.demo.eligibility.WorkloadSupport.Snapshot;
import com.acentra.catchy.demo.eligibility.WorkloadSupport.WorkloadSummary;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Service;

/**
 * Synthetic request patterns. Only synthetic {@code SYN-} ids are used; nothing is logged but counts. Each run is
 * synchronous and reports what it changed, computed from SDK metric deltas.
 */
@Service
public class WorkloadService {

    public static final int DEFAULT_REPEATED_REQUESTS = 500;
    public static final int DEFAULT_CHURN_REQUESTS = 2_000;

    private static final int HOT_MEMBERS = 12;
    private static final int WARM_MEMBERS = 40;
    private static final Duration SHORT_TTL = Duration.ofSeconds(1);

    private final EligibilityService eligibility;
    private final AuthorizationService authorization;
    private final AcentraCacheManager manager;
    private final DemoProperties props;
    private final List<AcentraCache<?, ?>> regions;

    private final AtomicLong churnCounter = new AtomicLong();
    private final AtomicLong runCounter = new AtomicLong();

    public WorkloadService(EligibilityService eligibility, AuthorizationService authorization, AcentraCacheManager manager,
                           DemoProperties props, AcentraCache<String, EligibilityResult> eligibilitySummaryCache,
                           AcentraCache<String, AuthorizationDecision> authorizationDecisionCache) {
        this.eligibility = eligibility;
        this.authorization = authorization;
        this.manager = manager;
        this.props = props;
        this.regions = List.of(eligibilitySummaryCache, authorizationDecisionCache);
    }

    public List<AcentraCache<?, ?>> regions() {
        return regions;
    }

    /** A few members are queried again and again: a high hit rate. */
    public WorkloadSummary repeated(int requests) {
        Random rnd = new Random(3_000 + runCounter.incrementAndGet());
        List<Runnable> tasks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            int n = rnd.nextDouble() < 0.8 ? 1 + (int) (HOT_MEMBERS * Math.pow(rnd.nextDouble(), 2.0)) : 100 + rnd.nextInt(WARM_MEMBERS);
            String id = memberId(n);
            tasks.add(() -> eligibility.lookup(id));
        }
        Snapshot before = Snapshot.of(regions);
        long t0 = System.nanoTime();
        long deadline = t0 + WorkloadSupport.MAX_RUN_NANOS;
        Outcome out = WorkloadSupport.run(tasks, deadline).plus(WorkloadSupport.run(authorizationFlows(2 + requests / 200), deadline));
        List<RegionDelta> deltas = before.deltaTo(Snapshot.of(regions), regions);
        return WorkloadSupport.summarize("repeated",
                "A few synthetic members are queried repeatedly (high hit rate), plus a few authorization flows "
                        + "(2 preliminary reads + 1 mandatory source validation each).", requests, out, t0, deltas, hint());
    }

    /**
     * Thousands of unique synthetic members: the region can hold ~128 entries (512 KB / 4 KB modeled footprint), so the
     * memory limit drives eviction. Every 5th entry gets a 1 s TTL so expirations show up too.
     */
    public WorkloadSummary highChurn(int requests) {
        long base = churnCounter.getAndAdd(requests);
        List<Runnable> tasks = new ArrayList<>(requests);
        for (int i = 0; i < requests; i++) {
            String id = String.format("SYN-%09d", 1_000_000L + base + i);
            Duration ttl = i % 5 == 4 ? SHORT_TTL : null;
            tasks.add(() -> eligibility.lookup(id, ttl));
        }
        Snapshot before = Snapshot.of(regions);
        long t0 = System.nanoTime();
        long deadline = t0 + WorkloadSupport.MAX_RUN_NANOS;
        Outcome out = WorkloadSupport.run(tasks, deadline).plus(WorkloadSupport.run(authorizationFlows(3), deadline));
        WorkloadSupport.sleepQuietly(SHORT_TTL.toMillis() + 200); // let the 1 s entries expire, then purge them now
        for (AcentraCache<?, ?> c : regions) c.cleanUp();
        List<RegionDelta> deltas = before.deltaTo(Snapshot.of(regions), regions);
        return WorkloadSupport.summarize("high-churn",
                "Unique synthetic members only: evictions (memory-limit driven), plus expirations of the short-TTL entries.",
                requests, out, t0, deltas, hint());
    }

    /** Each task: preliminary read twice (cache allowed), then the mandatory source validation (finalize). */
    private List<Runnable> authorizationFlows(int count) {
        List<Runnable> flows = new ArrayList<>(count);
        for (int j = 0; j < count; j++) {
            String id = String.format("SYN-%09d", 900_000_000L + j);
            flows.add(() -> {
                authorization.preliminary(id);
                authorization.preliminary(id);
                authorization.finalizeDecision(id);
            });
        }
        return flows;
    }

    private String hint() {
        boolean telemetryOn = manager.telemetry() != TelemetryClient.noop();
        return "Open the dashboard at " + props.getDashboardUrl() + " to watch these counters"
                + (telemetryOn ? " update live (eligibility-service)."
                : " (telemetry is disabled here: set CATCHY_ELIGIBILITY_API_KEY to send safe telemetry to the telemetry service).");
    }

    private static String memberId(int n) {
        return String.format("SYN-%06d", n);
    }
}

package com.acentra.catchy.telemetry.sim;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.CacheAction;
import com.acentra.cache.CacheMetrics;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.sim.AccessPatternSimulator;
import com.acentra.cache.sim.AccessPatternSimulator.CacheSpec;
import com.acentra.cache.sim.AccessPatternSimulator.PatternResult;
import com.acentra.cache.sim.AccessPatternSimulator.PolicyComparison;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.dto.OpsDtos.ComparisonRow;
import com.acentra.catchy.telemetry.dto.OpsDtos.SimulationResult;
import com.acentra.catchy.telemetry.dto.OpsDtos.SimulationStep;
import com.acentra.catchy.telemetry.ingest.IngestionService;
import com.acentra.catchy.telemetry.service.RecommendationService;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Runs the access-pattern simulations inside the telemetry service with the SDK's own caches and synthetic keys only.
 * The caches publish into an in-process telemetry client, and the events plus an exact snapshot per cache are fed
 * straight into {@link IngestionService} (no HTTP, no API key), so the simulated regions appear in the dashboard like any
 * other region. Runs are synchronous and serialized.
 */
@Service
public class SimulationService {
    private static final Logger log = LoggerFactory.getLogger(SimulationService.class);

    public static final Set<String> KINDS = Set.of("sample-workload", "high-load", "ttl-expiration", "policy-comparison");
    static final int MAX_EVENTS_PER_CACHE = 1500;
    private static final long MB = 1024L * 1024;

    private final ApplicationRepository applications;
    private final IngestionService ingestion;
    private final RecommendationService recommendations;
    private final Clock clock;
    private final Object lock = new Object();

    public SimulationService(ApplicationRepository applications, IngestionService ingestion,
                             RecommendationService recommendations, Clock clock) {
        this.applications = applications;
        this.ingestion = ingestion;
        this.recommendations = recommendations;
        this.clock = clock;
    }

    /** One cache created during a run together with its telemetry sink and its zero-state baseline snapshot. */
    private record SimCache(AcentraCache<String, String> cache, InProcessTelemetryClient client, String region,
                            RegionSnapshot baseline) {}

    /** The caches of one run; acts as the factory handed to the SDK's {@link AccessPatternSimulator}. */
    private static final class Session {
        final ApplicationService app;
        final int sampling;
        final List<SimCache> caches = new ArrayList<>();

        Session(ApplicationService app, int sampling) {
            this.app = app;
            this.sampling = sampling;
        }

        AcentraCache<String, String> create(CacheSpec spec) {
            InProcessTelemetryClient client = new InProcessTelemetryClient();
            CacheRegionConfig.Builder b = CacheRegionConfig.builder()
                    .regionName(spec.regionName())
                    .applicationName(app.name)
                    .environment(app.environment)
                    .defaultPolicy(spec.policy())
                    .maximumEntries(spec.maximumEntries())
                    .maximumMemoryBytes(spec.maximumMemoryBytes())
                    .defaultTtl(spec.defaultTtl())
                    .stampedeShieldEnabled(spec.stampedeShield())
                    .riskLevel(CacheRiskLevel.LOW)
                    .routineEventSampling(sampling)
                    .decisionEventCapacity(10)
                    .telemetryClient(client);
            if (spec.clock() != null) b.clock(spec.clock());
            AcentraCache<String, String> cache = new AcentraCache<>(b.build());
            caches.add(new SimCache(cache, client, spec.regionName(), cache.snapshot()));
            return cache;
        }
    }

    private record Outcome(List<PatternResult> results, List<SimulationStep> steps, String summary,
                           List<ComparisonRow> comparison, boolean sumSubRuns) {}

    public SimulationResult run(Long applicationId, String kind, Integer requests) {
        if (!KINDS.contains(kind)) throw ApiException.notFound("Unknown simulation '" + kind + "'");
        ApplicationService app = applications.findById(applicationId).orElseThrow(() -> ApiException.notFound("Application not found"));
        synchronized (lock) {
            Instant started = Times.now(clock);
            long t0 = System.nanoTime();
            int n = requests != null ? requests : defaultRequests(kind);
            Session session = new Session(app, samplingFor(kind));
            AccessPatternSimulator simulator = new AccessPatternSimulator(session::create);
            Outcome outcome;
            try {
                outcome = switch (kind) {
                    case "sample-workload" -> sampleWorkload(simulator, n);
                    case "high-load" -> highLoad(simulator, session, n);
                    case "ttl-expiration" -> ttlExpiration(simulator, session, n);
                    default -> policyComparison(simulator, n);
                };
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
            }
            List<String> regions = publish(app, session, outcome.sumSubRuns());
            try {
                recommendations.refresh(applicationId, false);
            } catch (RuntimeException e) {
                log.warn("Recommendation refresh after simulation failed: {}", e.getClass().getSimpleName());
            }
            long hits = 0, misses = 0, evictions = 0, expirations = 0;
            for (PatternResult r : outcome.results()) {
                hits += r.hits();
                misses += r.misses();
                evictions += r.evictions();
                expirations += r.expirations();
            }
            double rate = hits + misses == 0 ? 0.0 : Times.round2((double) hits / (hits + misses) * 100.0);
            long durationMs = (System.nanoTime() - t0) / 1_000_000L;
            return new SimulationResult(UUID.randomUUID().toString().substring(0, 6), kind, applicationId, regions, started,
                    durationMs, outcome.summary(), outcome.steps(), hits, misses, rate, evictions, expirations, outcome.comparison());
        }
    }

    // ---- the four simulations ----------------------------------------------------------------------------------------

    private Outcome sampleWorkload(AccessPatternSimulator sim, int n) {
        String region = "sim-sample-workload";
        List<PatternResult> results = new ArrayList<>();
        results.add(sim.replay("A", "Changing access", "A,B,C,A,D,E,D,E,F,G on a 4-entry LRU cache",
                new CacheSpec(region, EvictionPolicy.LRU, 4, 64 * MB, Duration.ofMinutes(5)), AccessPatternSimulator.patternASequence()));
        results.add(sim.replay("B", "Stable popularity", "A,A,A,A,B,B,B,C,D,E,A,B on a 4-entry LFU cache",
                new CacheSpec(region, EvictionPolicy.LFU, 4, 64 * MB, Duration.ofMinutes(5)), AccessPatternSimulator.patternBSequence()));
        results.add(sim.capacityEviction(region, EvictionPolicy.LRU));
        int half = Math.max(25, n / 2);
        results.add(sim.replay("A-long", "Changing access (long stream)",
                half + " read-through requests whose working set moves every 150 requests, LRU cache of 20 entries",
                new CacheSpec(region, EvictionPolicy.LRU, 20, 64 * MB, Duration.ofMinutes(10)),
                AccessPatternSimulator.changingAccessKeys(half, 42)));
        results.add(sim.replay("B-long", "Stable popularity (long stream)",
                (n - half) + " read-through requests: a few hot keys plus a long tail of one-off keys, LFU cache of 450 entries",
                new CacheSpec(region, EvictionPolicy.LFU, 450, 64 * MB, Duration.ofMinutes(10)),
                AccessPatternSimulator.stablePopularityKeys(n - half, 43)));
        long hits = 0, misses = 0, evictions = 0;
        for (PatternResult r : results) {
            hits += r.hits();
            misses += r.misses();
            evictions += r.evictions();
        }
        String summary = String.format(Locale.ROOT,
                "Replayed patterns A, B and D plus two long streams (%d requests): %.1f%% hit rate with %d evictions.",
                n, hits + misses == 0 ? 0.0 : (double) hits / (hits + misses) * 100.0, evictions);
        return new Outcome(results, stepsOf(results), summary, null, true);
    }

    private Outcome highLoad(AccessPatternSimulator sim, Session session, int n) throws InterruptedException {
        String region = "sim-high-load";
        List<PatternResult> results = new ArrayList<>();
        results.add(sim.memoryEviction(region));
        results.add(sim.concurrentAccess(region, 4, Math.max(200, n / 4)));
        results.add(sim.replay("H", "Overflowing working set",
                n + " requests over a changing working set that does not fit the 3-entry capacity",
                new CacheSpec(region, EvictionPolicy.LRU, 3, 64 * MB, Duration.ofMinutes(30)),
                AccessPatternSimulator.changingAccessKeys(n, 7)));
        long entry = 0, memory = 0;
        for (SimCache c : session.caches) {
            CacheMetrics m = c.cache().getMetrics();
            entry += m.evictionsDueToEntryLimit();
            memory += m.evictionsDueToMemoryLimit();
        }
        String summary = String.format(Locale.ROOT,
                "High load produced %d entry-limit and %d memory-limit evictions across memory pressure, %d concurrent "
                        + "operations and an overflowing working set.", entry, memory, Math.max(200, n / 4) * 4);
        return new Outcome(results, stepsOf(results), summary, null, true);
    }

    private Outcome ttlExpiration(AccessPatternSimulator sim, Session session, int n) throws InterruptedException {
        String region = "sim-ttl-expiration";
        List<PatternResult> results = new ArrayList<>();
        results.add(sim.ttlExpiration(region));

        int entries = Math.min(n, 1000);
        AcentraCache<String, String> cache = session.create(
                new CacheSpec(region, EvictionPolicy.LRU, 5_000, 64 * MB, Duration.ofMillis(250)));
        for (int i = 0; i < entries; i++) cache.put("ttl-batch-" + i, "synthetic", Duration.ofMillis(250));
        int before = 0;
        for (int i = 0; i < entries / 2; i++) if (cache.get("ttl-batch-" + i).isPresent()) before++;
        Thread.sleep(320);
        int after = 0;
        for (int i = 0; i < entries; i++) if (cache.get("ttl-batch-" + i).isPresent()) after++;
        CacheMetrics m = cache.getMetrics();
        double rate = m.hits() + m.misses() == 0 ? 0 : Times.round2((double) m.hits() / (m.hits() + m.misses()) * 100.0);
        results.add(new PatternResult("T", "Short-TTL batch", entries + " entries with a 250 ms TTL", entries + before + after,
                m.hits(), m.misses(), rate, m.evictions(), m.expirations(), after == 0,
                before + " hits before expiry, " + after + " hits after 320 ms (" + m.expirations() + " expirations recorded)"));
        String summary = String.format(Locale.ROOT,
                "TTL expiry is independent of eviction: %d short-TTL entries were served before expiry and all %d expired "
                        + "afterwards, recording MISS and EXPIRED events.", before, m.expirations());
        return new Outcome(results, stepsOf(results), summary, null, true);
    }

    private Outcome policyComparison(AccessPatternSimulator sim, int n) {
        List<PatternResult> results = new ArrayList<>();
        List<SimulationStep> steps = new ArrayList<>();
        List<ComparisonRow> rows = new ArrayList<>();
        PolicyComparison a = sim.compare("sim-policy", "A", "A — LRU-friendly changing access",
                "Recently accessed keys matter most; LRU keeps the moving working set.",
                AccessPatternSimulator.changingAccessKeys(n, 11), 8);
        PolicyComparison b = sim.compare("sim-policy", "B", "B — LFU-friendly stable popularity",
                "A few keys are consistently popular; LFU protects them from one-off scans.",
                AccessPatternSimulator.stablePopularityKeys(n, 12), 6);
        for (PolicyComparison c : List.of(a, b)) {
            results.add(c.lru());
            results.add(c.lfu());
            String pattern = c.pattern();
            steps.add(new SimulationStep("Pattern " + c.lru().id() + " on LRU", pattern, c.lru().detail()));
            steps.add(new SimulationStep("Pattern " + c.lfu().id() + " on LFU", pattern, c.lfu().detail()));
            rows.add(new ComparisonRow(pattern, c.lru().hitRate(), c.lfu().hitRate(), c.winner(), c.interpretation()));
        }
        String[] labels = {"changing-access", "stable-popularity"};
        List<PolicyComparison> compared = List.of(a, b);
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < compared.size(); i++) {
            PolicyComparison c = compared.get(i);
            if (summary.length() > 0) summary.append("; ");
            double diff = Math.abs(c.lru().hitRate() - c.lfu().hitRate());
            if (c.winner().equals("TIE")) {
                summary.append("LRU and LFU tie on the ").append(labels[i]).append(" pattern");
            } else {
                summary.append(c.winner()).append(" wins the ").append(labels[i]).append(" pattern")
                        .append(String.format(Locale.ROOT, " by %.1f pts", diff));
            }
        }
        summary.append('.');
        return new Outcome(results, steps, summary.toString(), rows, false);
    }

    // ---- publishing --------------------------------------------------------------------------------------------------

    /**
     * Feeds events and snapshots into ingestion. Regions fed by several sub-run caches are summed (one instance per
     * cache); for policy comparison only the most recent cache per region is published so its shadow data reflects the
     * last, decisive pattern.
     */
    private List<String> publish(ApplicationService app, Session session, boolean sumSubRuns) {
        sleepQuietly(3); // guarantees every final snapshot is newer than its baseline
        Map<String, List<SimCache>> byRegion = new LinkedHashMap<>();
        for (SimCache c : session.caches) byRegion.computeIfAbsent(c.region(), r -> new ArrayList<>()).add(c);
        for (Map.Entry<String, List<SimCache>> entry : byRegion.entrySet()) {
            List<SimCache> list = entry.getValue();
            int total = list.size();
            for (int i = 0; i < total; i++) {
                SimCache c = list.get(i);
                boolean publishSnapshots = sumSubRuns || i == total - 1;
                String instance = total == 1 ? "simulator" : "simulator-" + (i + 1);
                List<TelemetryEvent> events = downsample(c.client().drain(), MAX_EVENTS_PER_CACHE);
                List<RegionSnapshot> snapshots = publishSnapshots ? List.of(c.baseline(), c.cache().snapshot()) : List.of();
                ingestion.ingestInternal(app.id, instance, events, snapshots);
            }
        }
        return new ArrayList<>(byRegion.keySet());
    }

    /** Keeps every notable event class (evictions, expirations...) first, then thins out routine HIT/MISS/PUT evenly. */
    static List<TelemetryEvent> downsample(List<TelemetryEvent> events, int cap) {
        if (events.size() <= cap) return events;
        List<TelemetryEvent> notable = new ArrayList<>();
        List<TelemetryEvent> routine = new ArrayList<>();
        for (TelemetryEvent e : events) {
            CacheAction a = e.action();
            (a == CacheAction.HIT || a == CacheAction.MISS || a == CacheAction.PUT ? routine : notable).add(e);
        }
        List<TelemetryEvent> out = new ArrayList<>(stride(notable, cap / 2));
        out.addAll(stride(routine, cap - out.size()));
        out.sort((x, y) -> x.timestamp().compareTo(y.timestamp()));
        return out;
    }

    private static List<TelemetryEvent> stride(List<TelemetryEvent> list, int max) {
        if (list.size() <= max || max <= 0) return max <= 0 ? List.of() : list;
        List<TelemetryEvent> out = new ArrayList<>(max);
        double step = (double) list.size() / max;
        for (int i = 0; i < max; i++) out.add(list.get((int) (i * step)));
        return out;
    }

    private static List<SimulationStep> stepsOf(List<PatternResult> results) {
        return results.stream()
                .map(r -> new SimulationStep("Pattern " + r.id() + " — " + r.name(), r.description(), r.detail()))
                .toList();
    }

    private static int defaultRequests(String kind) {
        return switch (kind) {
            case "high-load" -> 5000;
            case "ttl-expiration" -> 200;
            case "policy-comparison" -> 3000;
            default -> 2000;
        };
    }

    private static int samplingFor(String kind) {
        return switch (kind) {
            case "ttl-expiration" -> 1;
            case "sample-workload" -> 5;
            default -> 10;
        };
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

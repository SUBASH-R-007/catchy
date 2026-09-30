package com.acentra.catchy.telemetry.service;

import com.acentra.cache.CachePolicyRecommendation;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.PolicyAdvisor;
import com.acentra.catchy.telemetry.ai.OllamaAdvisor;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.CachePolicyRecommendationEntity;
import com.acentra.catchy.telemetry.domain.PolicyChangeRequest;
import com.acentra.catchy.telemetry.domain.PolicyRequestRepository;
import com.acentra.catchy.telemetry.domain.RecommendationRepository;
import com.acentra.catchy.telemetry.dto.PolicyDtos.Recommendation;
import com.acentra.catchy.telemetry.service.AggregationService.RegionAggregate;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Policy Arena. The decision is always the SDK's deterministic {@link PolicyAdvisor}; the optional Ollama text only
 * fills {@code aiExplanation} and is requested solely from {@link #refresh(Long, boolean)} with {@code withAi=true}
 * (the evaluate endpoint and the background job), never from ingestion.
 */
@Service
public class RecommendationService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);
    private static final long AI_RETRY_SECONDS = 60;

    private final AggregationService aggregation;
    private final RecommendationRepository repository;
    private final PolicyRequestRepository requests;
    private final ApplicationRepository applications;
    private final OllamaAdvisor ollama;
    private final CatchyProperties props;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final ConcurrentHashMap<String, Instant> aiAttempts = new ConcurrentHashMap<>();

    public RecommendationService(AggregationService aggregation, RecommendationRepository repository,
                                 PolicyRequestRepository requests, ApplicationRepository applications,
                                 OllamaAdvisor ollama, CatchyProperties props, Clock clock,
                                 PlatformTransactionManager transactionManager) {
        this.aggregation = aggregation;
        this.repository = repository;
        this.requests = requests;
        this.applications = applications;
        this.ollama = ollama;
        this.props = props;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public PolicyAdvisor.Settings settings() {
        CatchyProperties.Advisor a = props.advisor();
        return new PolicyAdvisor.Settings(a.minRequests(), a.minImprovementPercent(), a.cooldown());
    }

    /** Deterministic evaluation of one region. No persistence, no side effects. */
    public CachePolicyRecommendation compute(RegionAggregate agg, Instant lastAppliedAt, Instant now) {
        var shadow = agg.shadow();
        long sample = shadow == null ? 0 : shadow.windowRequests();
        double lru = shadow == null ? 0 : shadow.lruHitRate();
        double lfu = shadow == null ? 0 : shadow.lfuHitRate();
        double concentration = shadow == null ? 0 : shadow.topKeyConcentrationPercent();
        Instant last = agg.lastPolicyChangeAt();
        if (lastAppliedAt != null && (last == null || lastAppliedAt.isAfter(last))) last = lastAppliedAt;
        return PolicyAdvisor.evaluate(new PolicyAdvisor.Input(agg.activePolicy(), sample, lru, lfu, concentration, last, now),
                settings());
    }

    /** "Keep LFU", "Switch claim-rules to LFU" or "No data yet" for an application's regions. */
    public String summarize(List<RegionAggregate> regions, Map<String, Instant> appliedAt, Instant now) {
        if (regions.isEmpty()) return "No data yet";
        for (RegionAggregate r : regions) {
            CachePolicyRecommendation rec = compute(r, appliedAt.get(r.cacheRegion()), now);
            if (rec.action() == CachePolicyRecommendation.Action.SWITCH) {
                return "Switch " + r.cacheRegion() + " to " + rec.recommendedPolicy();
            }
        }
        return "Keep " + new TreeSet<>(regions.stream().map(r -> r.activePolicy().name()).toList())
                .stream().collect(Collectors.joining("/"));
    }

    /** Latest APPLIED time per region of one application (feeds the cooldown). */
    public Map<String, Instant> appliedAt(Long applicationId) {
        return toAppliedMap(requests.findByApplicationIdAndStatusIn(applicationId, List.of(PolicyChangeRequest.APPLIED)));
    }

    public Map<Long, Map<String, Instant>> appliedAtAll() {
        Map<Long, List<PolicyChangeRequest>> byApp = requests.findByStatusIn(List.of(PolicyChangeRequest.APPLIED)).stream()
                .collect(Collectors.groupingBy(r -> r.applicationId));
        Map<Long, Map<String, Instant>> out = new HashMap<>();
        byApp.forEach((id, list) -> out.put(id, toAppliedMap(list)));
        return out;
    }

    private static Map<String, Instant> toAppliedMap(List<PolicyChangeRequest> applied) {
        Map<String, Instant> out = new HashMap<>();
        for (PolicyChangeRequest r : applied) {
            if (r.appliedAt != null) out.merge(r.cacheRegion, r.appliedAt, (a, b) -> a.isAfter(b) ? a : b);
        }
        return out;
    }

    // ---- stored results ---------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<Recommendation> stored(Long applicationId) {
        ApplicationService app = applications.findById(applicationId).orElseThrow(() -> ApiException.notFound("Application not found"));
        Map<String, Long> pending = pendingIds(requests.findByApplicationIdAndStatusIn(applicationId, List.of(PolicyChangeRequest.PENDING)));
        return repository.findByApplicationIdOrderByCacheRegion(applicationId).stream()
                .map(e -> toDto(e, app.name, pending.get(e.cacheRegion))).toList();
    }

    @Transactional(readOnly = true)
    public List<Recommendation> storedAll() {
        Map<Long, String> names = new HashMap<>();
        applications.findAll().forEach(a -> names.put(a.id, a.name));
        Map<String, Long> pending = pendingIds(requests.findByStatusIn(List.of(PolicyChangeRequest.PENDING)));
        List<Recommendation> out = new ArrayList<>();
        repository.findAll().stream()
                .sorted((a, b) -> a.applicationId.equals(b.applicationId)
                        ? a.cacheRegion.compareTo(b.cacheRegion) : a.applicationId.compareTo(b.applicationId))
                .forEach(e -> out.add(toDto(e, names.getOrDefault(e.applicationId, "unknown"),
                        pending.get(e.applicationId + "/" + e.cacheRegion))));
        return out;
    }

    // ---- evaluation --------------------------------------------------------------------------------------------------

    private record Refreshed(List<Recommendation> recommendations, Map<String, Double> concentration) {}

    /**
     * Re-evaluates every reported region of the application, stores the latest result per region (id is stable) and
     * returns it. With {@code withAi} and the Ollama advisor enabled, missing explanations are requested afterwards,
     * outside any database transaction.
     */
    public List<Recommendation> refresh(Long applicationId, boolean withAi) {
        Refreshed refreshed = tx.execute(s -> doRefresh(applicationId));
        if (!withAi || !ollama.enabled() || refreshed == null) {
            return refreshed == null ? List.of() : refreshed.recommendations();
        }
        boolean changed = false;
        Instant now = Times.now(clock);
        for (Recommendation r : refreshed.recommendations()) {
            if (r.aiExplanation() != null) continue;
            String key = r.applicationId() + "/" + r.cacheRegion();
            Instant last = aiAttempts.get(key);
            if (last != null && now.isBefore(last.plusSeconds(AI_RETRY_SECONDS))) continue;
            aiAttempts.put(key, now);
            Optional<String> text = ollama.explain(new OllamaAdvisor.Context(r.currentPolicy(), r.recommendedPolicy(), r.action(),
                    r.lruShadowHitRate(), r.lfuShadowHitRate(), r.sampleSize(), refreshed.concentration().getOrDefault(r.cacheRegion(), 0.0)));
            if (text.isPresent()) {
                tx.executeWithoutResult(s -> repository.findById(r.id()).ifPresent(e -> e.aiExplanation = text.get()));
                changed = true;
            }
        }
        if (changed) return tx.execute(s -> stored(applicationId));
        return refreshed.recommendations();
    }

    /**
     * Makes sure every region named here has a stored recommendation (first appearance of a region), so the overview is
     * complete before the next background run. Deterministic only (never calls Ollama); failures are swallowed so they can
     * never affect ingestion.
     */
    public void ensureStored(Long applicationId, java.util.Collection<String> regions) {
        if (regions.isEmpty()) return;
        try {
            Set<String> have = tx.execute(s -> repository.findByApplicationIdOrderByCacheRegion(applicationId).stream()
                    .map(e -> e.cacheRegion).collect(Collectors.toSet()));
            if (have == null || !have.containsAll(regions)) tx.execute(s -> doRefresh(applicationId));
        } catch (RuntimeException e) {
            log.debug("ensureStored skipped: {}", e.getClass().getSimpleName());
        }
    }

    /** Background job entry point: refreshes every application that has reported. */
    public void refreshAll(boolean withAi) {
        for (Long appId : aggregation.allByApplication().keySet()) {
            refresh(appId, withAi);
        }
    }

    private Refreshed doRefresh(Long applicationId) {
        ApplicationService app = applications.findById(applicationId).orElseThrow(() -> ApiException.notFound("Application not found"));
        List<RegionAggregate> regions = aggregation.forApplication(applicationId);
        Map<String, Instant> applied = appliedAt(applicationId);
        Map<String, Long> pending = pendingIds(requests.findByApplicationIdAndStatusIn(applicationId, List.of(PolicyChangeRequest.PENDING)));
        Instant now = Times.now(clock);
        List<Recommendation> out = new ArrayList<>();
        Map<String, Double> concentration = new HashMap<>();
        for (RegionAggregate agg : regions) {
            CachePolicyRecommendation calc = compute(agg, applied.get(agg.cacheRegion()), now);
            concentration.put(agg.cacheRegion(), agg.shadow() == null ? 0.0 : agg.shadow().topKeyConcentrationPercent());
            CachePolicyRecommendationEntity row = repository.findByApplicationIdAndCacheRegion(applicationId, agg.cacheRegion()).orElse(null);
            boolean isNew = row == null;
            if (isNew || differs(row, calc)) {
                boolean decisionChanged = isNew || !row.currentPolicy.equals(calc.currentPolicy().name())
                        || !row.recommendedPolicy.equals(calc.recommendedPolicy().name()) || !row.action.equals(calc.action().name());
                if (isNew) {
                    row = new CachePolicyRecommendationEntity();
                    row.applicationId = applicationId;
                    row.cacheRegion = agg.cacheRegion();
                }
                copy(calc, row);
                row.createdAt = now;
                if (decisionChanged) row.aiExplanation = null;
                row = repository.save(row);
            }
            out.add(toDto(row, app.name, pending.get(agg.cacheRegion())));
        }
        return new Refreshed(out, concentration);
    }

    private static boolean differs(CachePolicyRecommendationEntity e, CachePolicyRecommendation c) {
        return !e.currentPolicy.equals(c.currentPolicy().name())
                || !e.recommendedPolicy.equals(c.recommendedPolicy().name())
                || !e.action.equals(c.action().name())
                || e.lruShadowHitRate != c.lruShadowHitRate()
                || e.lfuShadowHitRate != c.lfuShadowHitRate()
                || e.improvementPercent != c.improvementPercent()
                || e.confidence != c.confidence()
                || !e.reason.equals(c.reason())
                || e.sampleSize != c.sampleSize()
                || e.minimumSampleMet != c.minimumSampleMet()
                || e.cooldownActive != c.cooldownActive()
                || !Objects.equals(e.cooldownEndsAt, c.cooldownEndsAt());
    }

    private static void copy(CachePolicyRecommendation c, CachePolicyRecommendationEntity e) {
        e.currentPolicy = c.currentPolicy().name();
        e.recommendedPolicy = c.recommendedPolicy().name();
        e.action = c.action().name();
        e.summary = c.summary();
        e.lruShadowHitRate = c.lruShadowHitRate();
        e.lfuShadowHitRate = c.lfuShadowHitRate();
        e.improvementPercent = c.improvementPercent();
        e.confidence = c.confidence();
        e.reason = c.reason().length() > 1000 ? c.reason().substring(0, 1000) : c.reason();
        e.sampleSize = c.sampleSize();
        e.minimumSampleMet = c.minimumSampleMet();
        e.cooldownActive = c.cooldownActive();
        e.cooldownEndsAt = c.cooldownEndsAt();
        e.approvalRequired = c.approvalRequired();
    }

    private static Map<String, Long> pendingIds(List<PolicyChangeRequest> pending) {
        Map<String, Long> out = new HashMap<>();
        for (PolicyChangeRequest r : pending) {
            out.merge(r.cacheRegion, r.id, Math::max);
            out.merge(r.applicationId + "/" + r.cacheRegion, r.id, Math::max);
        }
        return out;
    }

    private static Recommendation toDto(CachePolicyRecommendationEntity e, String applicationName, Long pendingRequestId) {
        return new Recommendation(e.id, e.applicationId, applicationName, e.cacheRegion,
                EvictionPolicy.valueOf(e.currentPolicy), EvictionPolicy.valueOf(e.recommendedPolicy), e.action, e.summary,
                e.lruShadowHitRate, e.lfuShadowHitRate, e.improvementPercent, e.confidence, e.reason, e.aiExplanation,
                e.sampleSize, e.minimumSampleMet, e.cooldownActive, e.cooldownEndsAt, e.approvalRequired, e.createdAt,
                pendingRequestId);
    }
}

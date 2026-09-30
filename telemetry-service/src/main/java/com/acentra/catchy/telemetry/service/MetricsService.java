package com.acentra.catchy.telemetry.service;

import com.acentra.cache.CacheHealthStatus;
import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.domain.Project;
import com.acentra.catchy.telemetry.domain.ProjectRepository;
import com.acentra.catchy.telemetry.dto.MetricTotals;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Alert;
import com.acentra.catchy.telemetry.dto.MetricsDtos.ApplicationMetrics;
import com.acentra.catchy.telemetry.dto.MetricsDtos.ApplicationSummary;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Health;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Overview;
import com.acentra.catchy.telemetry.dto.MetricsDtos.ProjectMetrics;
import com.acentra.catchy.telemetry.dto.MetricsDtos.RegionMetrics;
import com.acentra.catchy.telemetry.service.AggregationService.RegionAggregate;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Assembles the dashboard read models (overview, project, application, region) from the aggregated snapshots. */
@Service
public class MetricsService {

    private final AggregationService aggregation;
    private final ApplicationRepository applications;
    private final ProjectRepository projects;
    private final RecommendationService recommendations;
    private final AlertService alerts;
    private final Clock clock;

    public MetricsService(AggregationService aggregation, ApplicationRepository applications, ProjectRepository projects,
                          RecommendationService recommendations, AlertService alerts, Clock clock) {
        this.aggregation = aggregation;
        this.applications = applications;
        this.projects = projects;
        this.recommendations = recommendations;
        this.alerts = alerts;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        Instant now = Times.now(clock);
        List<ApplicationService> apps = applications.findAllByOrderById();
        Map<Long, String> projectNames = projectNames();
        Map<Long, List<RegionAggregate>> byApp = aggregation.allByApplication();
        Map<Long, Map<String, Instant>> applied = recommendations.appliedAtAll();

        List<ApplicationSummary> summaries = new ArrayList<>();
        List<RegionMetrics> regionViews = new ArrayList<>();
        List<Alert> activeAlerts = new ArrayList<>();
        List<MetricTotals> regionTotals = new ArrayList<>();
        for (ApplicationService app : apps) {
            List<RegionAggregate> regions = byApp.getOrDefault(app.id, List.of());
            summaries.add(summary(app, projectNames.get(app.projectId), regions, applied.getOrDefault(app.id, Map.of()), now));
            for (RegionAggregate r : regions) {
                regionViews.add(regionView(app, r));
                regionTotals.add(r.totals());
            }
            activeAlerts.addAll(alerts.alertsFor(app, regions));
        }
        Set<String> ids = new HashSet<>();
        activeAlerts.forEach(a -> ids.add(a.id()));
        alerts.retainOnly(ids);
        activeAlerts.sort(Comparator.comparing((Alert a) -> severityRank(a.severity())).reversed()
                .thenComparing(Alert::applicationId).thenComparing(Alert::cacheRegion));
        return new Overview(now, (int) projects.count(), apps.size(), regionViews.size(), MetricTotals.sum(regionTotals),
                activeAlerts, recommendations.storedAll(), summaries, regionViews);
    }

    @Transactional(readOnly = true)
    public ProjectMetrics project(Long projectId) {
        Project project = projects.findById(projectId).orElseThrow(() -> ApiException.notFound("Project not found"));
        Instant now = Times.now(clock);
        Map<Long, List<RegionAggregate>> byApp = aggregation.allByApplication();
        Map<Long, Map<String, Instant>> applied = recommendations.appliedAtAll();
        List<ApplicationSummary> summaries = new ArrayList<>();
        List<MetricTotals> totals = new ArrayList<>();
        List<Health> healths = new ArrayList<>();
        int regionCount = 0;
        for (ApplicationService app : applications.findByProjectIdOrderById(projectId)) {
            List<RegionAggregate> regions = byApp.getOrDefault(app.id, List.of());
            ApplicationSummary s = summary(app, project.name, regions, applied.getOrDefault(app.id, Map.of()), now);
            summaries.add(s);
            regionCount += regions.size();
            regions.forEach(r -> totals.add(r.totals()));
            if (!regions.isEmpty()) healths.add(s.health());
        }
        return new ProjectMetrics(project.id, project.name, summaries.size(), regionCount, MetricTotals.sum(totals),
                worst(healths), summaries);
    }

    @Transactional(readOnly = true)
    public ApplicationMetrics application(Long applicationId) {
        ApplicationService app = requireApplication(applicationId);
        Instant now = Times.now(clock);
        List<RegionAggregate> regions = aggregation.forApplication(applicationId);
        ApplicationSummary s = summary(app, projectName(app.projectId), regions, recommendations.appliedAt(applicationId), now);
        return new ApplicationMetrics(s, regions.stream().map(r -> regionView(app, r)).toList());
    }

    @Transactional(readOnly = true)
    public List<RegionMetrics> regions(Long applicationId) {
        ApplicationService app = requireApplication(applicationId);
        return aggregation.forApplication(applicationId).stream().map(r -> regionView(app, r)).toList();
    }

    @Transactional(readOnly = true)
    public RegionMetrics region(Long applicationId, String region) {
        ApplicationService app = requireApplication(applicationId);
        return regionView(app, requireRegion(applicationId, region));
    }

    @Transactional(readOnly = true)
    public Health regionHealth(Long applicationId, String region) {
        requireApplication(applicationId);
        return requireRegion(applicationId, region).health();
    }

    // ---- assembly ----------------------------------------------------------------------------------------------------

    ApplicationSummary summary(ApplicationService app, String projectName, List<RegionAggregate> regions,
                               Map<String, Instant> appliedAt, Instant now) {
        MetricTotals totals = MetricTotals.sum(regions.stream().map(RegionAggregate::totals).toList());
        Health health = regions.isEmpty()
                ? new Health(CacheHealthStatus.UNKNOWN, 0, List.of("No telemetry received yet."))
                : worst(regions.stream().map(RegionAggregate::health).toList());
        TreeSet<String> policies = new TreeSet<>();
        regions.forEach(r -> policies.add(r.activePolicy().name()));
        String label = policies.isEmpty() ? "NONE" : policies.size() == 1 ? policies.first() : "MIXED";
        Long since = app.lastTelemetryAt == null ? null : Math.max(0, Duration.between(app.lastTelemetryAt, now).getSeconds());
        return new ApplicationSummary(app.id, app.projectId, projectName, app.name, app.displayName, app.environment,
                regions.size(), totals, health, List.copyOf(policies), label,
                recommendations.summarize(regions, appliedAt, now), app.lastTelemetryAt, since);
    }

    static RegionMetrics regionView(ApplicationService app, RegionAggregate r) {
        return new RegionMetrics(app.id, app.name, app.environment, r.cacheRegion(), r.riskLevel(), r.activePolicy(),
                r.defaultTtlMs(), r.victimEnabled(), r.victimSize(), r.victimCapacity(), r.recentWindow(), r.shadow(),
                r.health(), r.instanceCount(), r.lastUpdated(), r.totals());
    }

    /** The health to show for a group: the most severe member. UNKNOWN only shows when nothing is known. */
    static Health worst(List<Health> healths) {
        if (healths.isEmpty()) return new Health(CacheHealthStatus.UNKNOWN, 0, List.of("No telemetry received yet."));
        return healths.stream().min(Comparator.comparing((Health h) -> -badness(h.status())).thenComparing(Health::score)).orElseThrow();
    }

    private static int badness(CacheHealthStatus s) {
        return switch (s) {
            case CRITICAL -> 4;
            case WARNING -> 3;
            case GOOD -> 2;
            case EXCELLENT -> 1;
            case UNKNOWN -> 0;
        };
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 3;
            case "WARNING" -> 2;
            default -> 1;
        };
    }

    private ApplicationService requireApplication(Long id) {
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("Application not found"));
    }

    private RegionAggregate requireRegion(Long applicationId, String region) {
        return aggregation.forRegion(applicationId, region).orElseThrow(() -> ApiException.notFound("Cache region not found"));
    }

    private Map<Long, String> projectNames() {
        Map<Long, String> names = new HashMap<>();
        projects.findAll().forEach(p -> names.put(p.id, p.name));
        return names;
    }

    private String projectName(Long projectId) {
        return projects.findById(projectId).map(p -> p.name).orElse("unknown");
    }
}

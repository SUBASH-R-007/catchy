package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.dto.MetricsDtos.ApplicationMetrics;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Health;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Overview;
import com.acentra.catchy.telemetry.dto.MetricsDtos.ProjectMetrics;
import com.acentra.catchy.telemetry.dto.MetricsDtos.RegionMetrics;
import com.acentra.catchy.telemetry.dto.MetricsDtos.Timeline;
import com.acentra.catchy.telemetry.dto.OpsDtos.Event;
import com.acentra.catchy.telemetry.dto.PolicyDtos.RegionConfig;
import com.acentra.catchy.telemetry.dto.PolicyDtos.UpdateRegionConfigRequest;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.service.EventQueryService;
import com.acentra.catchy.telemetry.service.MetricsService;
import com.acentra.catchy.telemetry.service.RegionConfigService;
import com.acentra.catchy.telemetry.service.TimelineService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dashboard read models: metrics, health, events, timelines and region configuration. */
@RestController
@RequestMapping("/api/v1")
public class MetricsController {

    private final MetricsService metrics;
    private final TimelineService timelines;
    private final EventQueryService events;
    private final RegionConfigService regionConfigs;

    public MetricsController(MetricsService metrics, TimelineService timelines, EventQueryService events,
                             RegionConfigService regionConfigs) {
        this.metrics = metrics;
        this.timelines = timelines;
        this.events = events;
        this.regionConfigs = regionConfigs;
    }

    @GetMapping("/overview")
    public Overview overview() {
        return metrics.overview();
    }

    @GetMapping("/projects/{projectId}/metrics")
    public ProjectMetrics projectMetrics(@PathVariable Long projectId) {
        return metrics.project(projectId);
    }

    @GetMapping("/applications/{applicationId}/metrics")
    public ApplicationMetrics applicationMetrics(@PathVariable Long applicationId) {
        return metrics.application(applicationId);
    }

    @GetMapping("/applications/{applicationId}/regions")
    public List<RegionMetrics> regions(@PathVariable Long applicationId) {
        return metrics.regions(applicationId);
    }

    @GetMapping("/applications/{applicationId}/regions/{regionName}/metrics")
    public RegionMetrics regionMetrics(@PathVariable Long applicationId, @PathVariable String regionName) {
        return metrics.region(applicationId, regionName);
    }

    @GetMapping("/applications/{applicationId}/regions/{regionName}/health")
    public Health regionHealth(@PathVariable Long applicationId, @PathVariable String regionName) {
        return metrics.regionHealth(applicationId, regionName);
    }

    @GetMapping("/applications/{applicationId}/regions/{regionName}/events")
    public List<Event> regionEvents(@PathVariable Long applicationId, @PathVariable String regionName,
                                    @RequestParam(required = false) Integer limit,
                                    @RequestParam(required = false) String actions,
                                    @RequestParam(required = false) String since) {
        return events.query(applicationId, regionName, limit, actions, since);
    }

    @GetMapping("/applications/{applicationId}/events")
    public List<Event> applicationEvents(@PathVariable Long applicationId,
                                         @RequestParam(required = false) Integer limit,
                                         @RequestParam(required = false) String actions,
                                         @RequestParam(required = false) String since) {
        return events.query(applicationId, null, limit, actions, since);
    }

    @GetMapping("/applications/{applicationId}/regions/{regionName}/timeline")
    public Timeline regionTimeline(@PathVariable Long applicationId, @PathVariable String regionName,
                                   @RequestParam(defaultValue = "15") int minutes,
                                   @RequestParam(defaultValue = "10") int bucketSeconds) {
        return timelines.timeline(applicationId, regionName, minutes, bucketSeconds);
    }

    @GetMapping("/applications/{applicationId}/timeline")
    public Timeline applicationTimeline(@PathVariable Long applicationId,
                                        @RequestParam(defaultValue = "15") int minutes,
                                        @RequestParam(defaultValue = "10") int bucketSeconds) {
        return timelines.timeline(applicationId, null, minutes, bucketSeconds);
    }

    @GetMapping("/timeline")
    public Timeline globalTimeline(@RequestParam(defaultValue = "15") int minutes,
                                   @RequestParam(defaultValue = "10") int bucketSeconds) {
        return timelines.timeline(null, null, minutes, bucketSeconds);
    }

    @GetMapping("/applications/{applicationId}/regions/{regionName}/config")
    public RegionConfig regionConfig(@PathVariable Long applicationId, @PathVariable String regionName) {
        return regionConfigs.get(applicationId, regionName);
    }

    @PutMapping("/applications/{applicationId}/regions/{regionName}/config")
    public RegionConfig updateRegionConfig(@PathVariable Long applicationId, @PathVariable String regionName,
                                           @Valid @RequestBody UpdateRegionConfigRequest body,
                                           @AuthenticationPrincipal AuthenticatedUser user) {
        return regionConfigs.update(applicationId, regionName, body, user);
    }
}

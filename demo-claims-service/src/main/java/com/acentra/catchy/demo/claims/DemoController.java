package com.acentra.catchy.demo.claims;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.catchy.demo.claims.CacheViews.RegionView;
import com.acentra.catchy.demo.claims.WorkloadSupport.WorkloadSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Demo-only endpoints: synthetic workloads and a local view of the caches (safe fields only). */
@RestController
public class DemoController {

    /** Optional body of the workload endpoints: {@code { "requests": 300 }}. */
    public record WorkloadRequest(@Min(10) @Max(5000) Integer requests) {}

    private final WorkloadService workloads;
    private final AcentraCacheManager manager;

    public DemoController(WorkloadService workloads, AcentraCacheManager manager) {
        this.workloads = workloads;
        this.manager = manager;
    }

    @PostMapping("/api/demo/claims/workload/repeated")
    public WorkloadSummary repeated(@RequestBody(required = false) @Valid WorkloadRequest body) {
        return workloads.repeated(requests(body, WorkloadService.DEFAULT_REQUESTS));
    }

    @PostMapping("/api/demo/claims/workload/changing")
    public WorkloadSummary changing(@RequestBody(required = false) @Valid WorkloadRequest body) {
        return workloads.changing(requests(body, WorkloadService.DEFAULT_REQUESTS));
    }

    @PostMapping("/api/demo/claims/workload/expire")
    public WorkloadSummary expire(@RequestBody(required = false) @Valid WorkloadRequest body) {
        return workloads.expire(requests(body, WorkloadService.DEFAULT_EXPIRE_REQUESTS));
    }

    @PostMapping("/api/demo/claims/workload/stampede")
    public WorkloadSummary stampede(@RequestBody(required = false) @Valid WorkloadRequest body) {
        return workloads.stampede(requests(body, WorkloadService.DEFAULT_STAMPEDE_CALLERS));
    }

    @GetMapping("/api/demo/claims/cache")
    public Map<String, Object> cache() {
        List<RegionView> views = new ArrayList<>();
        for (AcentraCache<?, ?> c : workloads.regions()) views.add(CacheViews.of(c));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("application", manager.applicationName());
        out.put("environment", manager.environment());
        out.put("telemetryEnabled", manager.telemetry() != TelemetryClient.noop());
        out.put("regions", views);
        return out;
    }

    @PostMapping("/api/demo/claims/cache/clear")
    public Map<String, Object> clear() {
        Map<String, Integer> cleared = new LinkedHashMap<>();
        for (AcentraCache<?, ?> c : workloads.regions()) {
            cleared.put(c.getConfig().regionName(), c.size());
            c.clear();
        }
        return Map.of("clearedEntries", cleared);
    }

    private static int requests(WorkloadRequest body, int fallback) {
        return body == null || body.requests() == null ? fallback : body.requests();
    }
}

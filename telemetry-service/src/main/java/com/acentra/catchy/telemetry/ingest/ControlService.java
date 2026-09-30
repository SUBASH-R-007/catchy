package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.ControlResponse;
import com.acentra.catchy.telemetry.domain.PolicyChangeRequest;
import com.acentra.catchy.telemetry.domain.PolicyRequestRepository;
import com.acentra.catchy.telemetry.domain.RegionConfigOverride;
import com.acentra.catchy.telemetry.domain.RegionConfigRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the SDK should apply: the latest APPROVED/APPLIED policy request and the admin-desired tuning of every region of
 * the calling application. Regions without either are omitted, so an idle application gets {@code {"regions": []}}.
 */
@Service
public class ControlService {

    private final PolicyRequestRepository requests;
    private final RegionConfigRepository configs;

    public ControlService(PolicyRequestRepository requests, RegionConfigRepository configs) {
        this.requests = requests;
        this.configs = configs;
    }

    @Transactional(readOnly = true)
    public ControlResponse controlFor(Long applicationId) {
        Map<String, PolicyChangeRequest> latestPolicy = new HashMap<>();
        for (PolicyChangeRequest r : requests.findByApplicationIdAndStatusIn(applicationId,
                List.of(PolicyChangeRequest.APPROVED, PolicyChangeRequest.APPLIED))) {
            latestPolicy.merge(r.cacheRegion, r, (a, b) -> a.id >= b.id ? a : b);
        }
        Map<String, RegionConfigOverride> tuning = new HashMap<>();
        for (RegionConfigOverride o : configs.findByApplicationId(applicationId)) tuning.put(o.cacheRegion, o);

        TreeSet<String> regions = new TreeSet<>(latestPolicy.keySet());
        regions.addAll(tuning.keySet());
        List<ControlResponse.RegionControl> out = new ArrayList<>();
        for (String region : regions) {
            PolicyChangeRequest p = latestPolicy.get(region);
            RegionConfigOverride o = tuning.get(region);
            out.add(new ControlResponse.RegionControl(region,
                    p == null ? null : EvictionPolicy.valueOf(p.requestedPolicy),
                    p == null ? null : p.id,
                    o == null ? null : new ControlResponse.Tuning(o.maximumEntries, o.maximumMemoryBytes, o.defaultTtlMs),
                    o == null ? null : o.tuningVersion));
        }
        return new ControlResponse(out);
    }
}

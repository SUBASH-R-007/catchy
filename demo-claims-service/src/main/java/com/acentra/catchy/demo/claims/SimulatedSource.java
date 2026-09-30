package com.acentra.catchy.demo.claims;

import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupSummary;
import com.acentra.catchy.demo.claims.ClaimsModels.Rule;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Fakes the claims database / rules API behind the caches: every call sleeps 10-30 ms (configurable), is counted, and
 * returns deterministic synthetic data derived from the id. No patient data exists anywhere in this class.
 */
@Component
public class SimulatedSource {

    private static final String[] CATEGORIES = {"BENEFIT_LIMIT", "PRIOR_AUTH", "DUPLICATE_CHECK", "MODIFIER_EDIT", "TIMELY_FILING",
            "COORDINATION_OF_BENEFITS", "NETWORK_CHECK", "BUNDLING"};
    private static final String[] ACTIONS = {"FLAG_FOR_REVIEW", "DENY_WITH_CODE", "PEND_FOR_DOCUMENTS", "ADJUST_PAYMENT", "ALLOW"};
    private static final String[] SEVERITIES = {"LOW", "MEDIUM", "HIGH"};
    private static final String[] SPECIALTIES = {"Primary Care", "Cardiology", "Orthopedics", "Behavioral Health", "Pediatrics",
            "Radiology", "Physical Therapy", "Dermatology"};
    private static final String[] REGIONS = {"NORTH", "SOUTH", "EAST", "WEST", "CENTRAL", "COASTAL"};

    private final int minMs;
    private final int maxMs;
    private final AtomicLong ruleSetCalls = new AtomicLong();
    private final AtomicLong providerCalls = new AtomicLong();

    public SimulatedSource(DemoProperties props) {
        int lo = Math.max(0, props.getSource().getLatencyMinMs());
        this.minMs = lo;
        this.maxMs = Math.max(lo, props.getSource().getLatencyMaxMs());
    }

    public RuleSet loadRuleSet(String ruleSetId) {
        simulateLatency();
        ruleSetCalls.incrementAndGet();
        Random r = new Random(seed(ruleSetId));
        int count = 9 + r.nextInt(3);
        List<Rule> rules = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String category = CATEGORIES[r.nextInt(CATEGORIES.length)];
            String condition = "procedureGroup IN ['PG-" + (1000 + r.nextInt(9000)) + "','PG-" + (1000 + r.nextInt(9000))
                    + "'] AND units > " + (1 + r.nextInt(6)) + " AND placeOfService != 'POS-" + (10 + r.nextInt(80)) + "'";
            rules.add(new Rule("R-" + Integer.toHexString(r.nextInt(0xFFFFF)).toUpperCase() + "-" + i, category, condition,
                    ACTIONS[r.nextInt(ACTIONS.length)], SEVERITIES[r.nextInt(SEVERITIES.length)]));
        }
        String version = "2026." + (1 + r.nextInt(4)) + "." + r.nextInt(10);
        return new RuleSet(ruleSetId, version, List.copyOf(rules));
    }

    public ProviderGroupSummary loadProvider(String providerGroupId) {
        simulateLatency();
        providerCalls.incrementAndGet();
        Random r = new Random(seed(providerGroupId));
        List<String> regions = new ArrayList<>();
        int regionCount = 1 + r.nextInt(3);
        for (int i = 0; i < regionCount; i++) {
            String region = REGIONS[r.nextInt(REGIONS.length)];
            if (!regions.contains(region)) regions.add(region);
        }
        return new ProviderGroupSummary(providerGroupId, "Synthetic Provider Group " + (100 + r.nextInt(900)),
                SPECIALTIES[r.nextInt(SPECIALTIES.length)], r.nextInt(10) < 8 ? "IN_NETWORK" : "OUT_OF_NETWORK",
                5 + r.nextInt(120), List.copyOf(regions), "dir-2026-" + (1 + r.nextInt(9)));
    }

    public long ruleSetCalls() { return ruleSetCalls.get(); }
    public long providerCalls() { return providerCalls.get(); }
    public long totalCalls() { return ruleSetCalls.get() + providerCalls.get(); }

    private void simulateLatency() {
        long ms = minMs == maxMs ? minMs : ThreadLocalRandom.current().nextLong(minMs, maxMs + 1L);
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** FNV-1a over the id: stable across JVMs, unlike anything identity based. */
    private static long seed(String id) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }
}

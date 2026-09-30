package com.acentra.catchy.demo.eligibility;

import com.acentra.catchy.demo.eligibility.EligibilityModels.AuthorizationDecision;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResult;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Fakes the eligibility / authorization system of record. Every call sleeps a few milliseconds (configurable), is counted,
 * and returns deterministic synthetic data derived from the synthetic id. A mutable override per id lets the demo change
 * the source ("upstream change") so cached values drift. No real patient data exists anywhere in this class.
 */
@Component
public class SimulatedSource {

    private static final String BASELINE_AS_OF = "2026-01-01";
    private static final String[] TIERS = {"GOLD", "SILVER", "BRONZE", "PLATINUM"};
    private static final String[] AUTH_STATUSES = {"APPROVED", "DENIED", "PENDING_REVIEW"};

    private record EligibilityOverride(boolean active, String asOf) {}

    private record AuthorizationOverride(String status, String asOf) {}

    private final int minMs;
    private final int maxMs;
    private final AtomicLong eligibilityCalls = new AtomicLong();
    private final AtomicLong authorizationCalls = new AtomicLong();
    private final Map<String, EligibilityOverride> eligibilityOverrides = new ConcurrentHashMap<>();
    private final Map<String, AuthorizationOverride> authorizationOverrides = new ConcurrentHashMap<>();

    public SimulatedSource(DemoProperties props) {
        int lo = Math.max(0, props.getSource().getLatencyMinMs());
        this.minMs = lo;
        this.maxMs = Math.max(lo, props.getSource().getLatencyMaxMs());
    }

    // ---- eligibility ----------------------------------------------------------------------------------------------

    /** A source call: sleeps, counts. */
    public EligibilityResult loadEligibility(String syntheticMemberId) {
        simulateLatency();
        eligibilityCalls.incrementAndGet();
        return eligibilityNow(syntheticMemberId);
    }

    /** What the source currently says, without a simulated call (used to report a change). */
    EligibilityResult eligibilityNow(String id) {
        EligibilityOverride o = eligibilityOverrides.get(id);
        if (o != null) return result(id, o.active(), o.asOf());
        return result(id, mix(id) % 10 < 8, BASELINE_AS_OF);
    }

    /** Simulates an upstream change: flips the member's active status in the source. @return the new source value. */
    public EligibilityResult flipEligibility(String id) {
        boolean nowActive = !eligibilityNow(id).active();
        eligibilityOverrides.put(id, new EligibilityOverride(nowActive, LocalDate.now().toString()));
        return eligibilityNow(id);
    }

    private static EligibilityResult result(String id, boolean active, String asOf) {
        String tier = active ? TIERS[(int) (mix(id + "#tier") % TIERS.length)] : "NONE";
        return new EligibilityResult(active, tier, asOf);
    }

    // ---- authorization --------------------------------------------------------------------------------------------

    public AuthorizationDecision loadAuthorization(String syntheticRequestId) {
        simulateLatency();
        authorizationCalls.incrementAndGet();
        return authorizationNow(syntheticRequestId);
    }

    AuthorizationDecision authorizationNow(String id) {
        AuthorizationOverride o = authorizationOverrides.get(id);
        if (o != null) return new AuthorizationDecision(o.status(), o.asOf());
        return new AuthorizationDecision(AUTH_STATUSES[(int) (mix(id + "#auth") % AUTH_STATUSES.length)], BASELINE_AS_OF);
    }

    /** Simulates an upstream decision change: moves the request to the next status. @return the new source value. */
    public AuthorizationDecision changeAuthorization(String id) {
        String current = authorizationNow(id).status();
        String next = AUTH_STATUSES[0];
        for (int i = 0; i < AUTH_STATUSES.length; i++) {
            if (AUTH_STATUSES[i].equals(current)) next = AUTH_STATUSES[(i + 1) % AUTH_STATUSES.length];
        }
        authorizationOverrides.put(id, new AuthorizationOverride(next, LocalDate.now().toString()));
        return authorizationNow(id);
    }

    public long eligibilityCalls() { return eligibilityCalls.get(); }
    public long authorizationCalls() { return authorizationCalls.get(); }
    public long totalCalls() { return eligibilityCalls.get() + authorizationCalls.get(); }

    private void simulateLatency() {
        long ms = minMs == maxMs ? minMs : ThreadLocalRandom.current().nextLong(minMs, maxMs + 1L);
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stable, well-spread non-negative hash of the id (FNV-1a followed by a murmur3 finalizer). */
    private static long mix(String id) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) {
            h ^= id.charAt(i);
            h *= 0x100000001b3L;
        }
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h & Long.MAX_VALUE;
    }
}

package com.acentra.cache.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.acentra.cache.sim.AccessPatternSimulator.PatternResult;
import com.acentra.cache.sim.AccessPatternSimulator.PolicyComparison;
import com.acentra.cache.EvictionPolicy;
import org.junit.jupiter.api.Test;

class AccessPatternSimulatorTest {
    private final AccessPatternSimulator sim = AccessPatternSimulator.standalone();

    @Test
    void changingAccessPatternFavorsLru() {
        PolicyComparison c = sim.compare("sim-a", "A", "LRU-friendly changing access", "recent keys matter",
                AccessPatternSimulator.changingAccessKeys(1500, 7), 8);
        assertThat(c.winner()).isEqualTo("LRU");
        assertThat(c.lru().hitRate()).isGreaterThan(c.lfu().hitRate() + 5);
    }

    @Test
    void stablePopularityPatternFavorsLfu() {
        PolicyComparison c = sim.compare("sim-b", "B", "LFU-friendly stable popularity", "popular keys stay popular",
                AccessPatternSimulator.stablePopularityKeys(3000, 7), 6);
        assertThat(c.winner()).isEqualTo("LFU");
        assertThat(c.lfu().hitRate()).isGreaterThan(c.lru().hitRate() + 5);
    }

    @Test
    void literalSpecSequencesRunAndAccountForEveryRequest() {
        PatternResult a = sim.replay("A", "a", "d", new AccessPatternSimulator.CacheSpec("sim-lit-a", EvictionPolicy.LRU, 3, 1 << 20,
                java.time.Duration.ofMinutes(5)), AccessPatternSimulator.patternASequence());
        PatternResult b = sim.replay("B", "b", "d", new AccessPatternSimulator.CacheSpec("sim-lit-b", EvictionPolicy.LFU, 3, 1 << 20,
                java.time.Duration.ofMinutes(5)), AccessPatternSimulator.patternBSequence());
        assertThat(a.hits() + a.misses()).isEqualTo(10);
        assertThat(b.hits() + b.misses()).isEqualTo(12);
        assertThat(b.hits()).isGreaterThan(0);
    }

    @Test
    void ttlExpirationRecordsMissAndExpiration() throws Exception {
        PatternResult r = sim.ttlExpiration("sim-ttl");
        assertThat(r.expectationMet()).isTrue();
        assertThat(r.expirations()).isEqualTo(1);
    }

    @Test
    void capacityEvictionUnderLruEvictsB() {
        PatternResult r = sim.capacityEviction("sim-cap", EvictionPolicy.LRU);
        assertThat(r.expectationMet()).isTrue();
        assertThat(r.detail()).contains("[A, C, D]");
    }

    @Test
    void memoryEvictionProducesMemoryEvictions() {
        assertThat(sim.memoryEviction("sim-mem").expectationMet()).isTrue();
    }

    @Test
    void concurrentAccessKeepsInvariants() throws Exception {
        PatternResult r = sim.concurrentAccess("sim-conc", 8, 3000);
        assertThat(r.expectationMet()).as(r.detail()).isTrue();
    }

    @Test
    void stampedeSimulationMakesExactlyOneSourceCall() throws Exception {
        PatternResult r = sim.stampede("sim-stampede", 50);
        assertThat(r.expectationMet()).as(r.detail()).isTrue();
    }
}

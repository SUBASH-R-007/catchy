package com.acentra.catchy.telemetry.sim;

import static org.assertj.core.api.Assertions.assertThat;

import com.acentra.cache.CacheAction;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SimulationUnitTest {

    private static TelemetryEvent event(CacheAction action, int i) {
        return new TelemetryEvent(Instant.parse("2026-09-30T09:00:00Z").plusMillis(i), "a", "e", "r", action, null, "x",
                EvictionPolicy.LRU, 0, 0, 0, 0, 0, 0, 0, 0, false, EventSeverity.INFO, 0);
    }

    @Test
    void downsamplingKeepsNotableEventsAndCapsRoutineOnes() {
        List<TelemetryEvent> events = new ArrayList<>();
        int i = 0;
        for (; i < 5_000; i++) events.add(event(CacheAction.MISS, i));
        for (int k = 0; k < 40; k++, i++) events.add(event(CacheAction.MEMORY_EVICTED, i));
        for (int k = 0; k < 10; k++, i++) events.add(event(CacheAction.EXPIRED, i));

        List<TelemetryEvent> sampled = SimulationService.downsample(events, 1_000);
        assertThat(sampled).hasSizeLessThanOrEqualTo(1_000);
        assertThat(sampled.stream().filter(e -> e.action() == CacheAction.MEMORY_EVICTED)).hasSize(40);
        assertThat(sampled.stream().filter(e -> e.action() == CacheAction.EXPIRED)).hasSize(10);
        assertThat(sampled.stream().filter(e -> e.action() == CacheAction.MISS)).hasSize(950);
        assertThat(sampled).isSortedAccordingTo((x, y) -> x.timestamp().compareTo(y.timestamp()));
    }

    @Test
    void smallEventListsAreKeptIntact() {
        List<TelemetryEvent> events = List.of(event(CacheAction.HIT, 1), event(CacheAction.MISS, 2));
        assertThat(SimulationService.downsample(events, 10)).isSameAs(events);
    }
}

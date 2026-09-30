package com.acentra.catchy.telemetry.sim;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.cache.telemetry.TelemetryEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Telemetry sink for simulation caches: collects the safe events in memory so the simulation service can feed its own
 * ingestion directly (no HTTP, no API key). Snapshots are taken explicitly by the simulation, not pushed here.
 */
final class InProcessTelemetryClient implements TelemetryClient {
    private final ConcurrentLinkedQueue<TelemetryEvent> events = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<String, LongAdder> sent = new ConcurrentHashMap<>();

    @Override
    public void publishEvent(TelemetryEvent event) {
        if (event == null) return;
        events.add(event);
        sent.computeIfAbsent(event.cacheRegion(), r -> new LongAdder()).increment();
    }

    @Override
    public void publishSnapshot(RegionSnapshot snapshot) {
        // snapshots are captured by the simulation itself after the run
    }

    @Override
    public long eventsSent(String region) {
        LongAdder a = sent.get(region);
        return a == null ? 0 : a.sum();
    }

    @Override
    public long eventsFailed(String region) {
        return 0;
    }

    @Override
    public int failureStreak() {
        return 0;
    }

    @Override
    public void close() {
        // nothing to release
    }

    List<TelemetryEvent> drain() {
        List<TelemetryEvent> out = new ArrayList<>(events.size());
        TelemetryEvent e;
        while ((e = events.poll()) != null) out.add(e);
        return out;
    }
}

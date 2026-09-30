package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.cache.telemetry.TelemetryEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/** In-process stand-in for the telemetry service: captures exactly what the SDK would send. No network. */
class RecordingTelemetryClient implements TelemetryClient {
    private final Queue<TelemetryEvent> events = new ConcurrentLinkedQueue<>();
    private final Queue<RegionSnapshot> snapshots = new ConcurrentLinkedQueue<>();

    @Override
    public void publishEvent(TelemetryEvent event) {
        events.add(event);
    }

    @Override
    public void publishSnapshot(RegionSnapshot snapshot) {
        snapshots.add(snapshot);
        while (snapshots.size() > 200) snapshots.poll();
    }

    List<TelemetryEvent> events() {
        return new ArrayList<>(events);
    }

    List<RegionSnapshot> snapshots() {
        return new ArrayList<>(snapshots);
    }

    @Override
    public long eventsSent(String region) {
        return 0;
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
    public void close() {}
}

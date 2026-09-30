package com.acentra.cache.telemetry;

/** Discards everything. Default when no telemetry endpoint is configured. */
final class NoopTelemetryClient implements TelemetryClient {
    static final NoopTelemetryClient INSTANCE = new NoopTelemetryClient();

    private NoopTelemetryClient() {}

    @Override public void publishEvent(TelemetryEvent event) {}
    @Override public void publishSnapshot(RegionSnapshot snapshot) {}
    @Override public long eventsSent(String region) { return 0; }
    @Override public long eventsFailed(String region) { return 0; }
    @Override public int failureStreak() { return 0; }
    @Override public void close() {}
}

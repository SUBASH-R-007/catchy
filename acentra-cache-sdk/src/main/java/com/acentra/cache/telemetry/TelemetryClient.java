package com.acentra.cache.telemetry;

/**
 * Non-blocking sink for safe cache telemetry. Implementations must never throw into the cache and must never
 * block the caller: cache GET/PUT stay fast even when the telemetry service is down.
 */
public interface TelemetryClient extends AutoCloseable {

    /** Queue an event. Never blocks, never throws. */
    void publishEvent(TelemetryEvent event);

    /** Replace the pending snapshot of that region (snapshots are cumulative, only the latest matters). */
    void publishSnapshot(RegionSnapshot snapshot);

    /** Events of the region delivered successfully so far. */
    long eventsSent(String region);

    /** Events / snapshots of the region whose delivery failed (per failed attempt) or were dropped. */
    long eventsFailed(String region);

    /** Consecutive failed delivery attempts (0 when the last attempt succeeded). */
    int failureStreak();

    default void setControlListener(ControlListener listener) {}

    /** Best-effort immediate delivery attempt (used on shutdown and in tests). */
    default void flush() {}

    @Override
    void close();

    static TelemetryClient noop() {
        return NoopTelemetryClient.INSTANCE;
    }
}

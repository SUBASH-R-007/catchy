package com.acentra.catchy.telemetry.security;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import java.time.Clock;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** Simple in-memory fixed-window limiter (requests per minute) for API-key authenticated ingestion. */
@Component
public class RateLimiter {

    private static final class Window {
        long minute;
        int count;
    }

    private final ConcurrentHashMap<Long, Window> windows = new ConcurrentHashMap<>();
    private final int limitPerMinute;
    private final Clock clock;

    public RateLimiter(CatchyProperties props, Clock clock) {
        this.limitPerMinute = props.ingest().rateLimitPerMinute();
        this.clock = clock;
    }

    /** @return true when the request is allowed, false when the key exceeded its per-minute budget. */
    public boolean tryAcquire(long apiKeyId) {
        long minute = clock.millis() / 60_000L;
        Window w = windows.computeIfAbsent(apiKeyId, k -> new Window());
        synchronized (w) {
            if (w.minute != minute) {
                w.minute = minute;
                w.count = 0;
            }
            if (w.count >= limitPerMinute) return false;
            w.count++;
            return true;
        }
    }

    public int secondsUntilReset() {
        long ms = clock.millis();
        return (int) (60 - (ms / 1000) % 60);
    }
}

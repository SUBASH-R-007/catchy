package com.acentra.catchy.telemetry.config;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Server-side "now", truncated to milliseconds so JSON output stays clean and DB round-trips are exact. */
public final class Times {
    private Times() {}

    public static Instant now(Clock clock) {
        return Instant.now(clock).truncatedTo(ChronoUnit.MILLIS);
    }

    public static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}

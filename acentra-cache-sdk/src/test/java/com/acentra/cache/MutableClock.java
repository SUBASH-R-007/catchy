package com.acentra.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Deterministic, manually advanced clock for TTL tests. */
final class MutableClock extends Clock {
    private volatile long millis;

    MutableClock(long startMillis) {
        this.millis = startMillis;
    }

    MutableClock() {
        this(1_700_000_000_000L);
    }

    void advance(Duration d) {
        millis += d.toMillis();
    }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public long millis() { return millis; }
    @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
}

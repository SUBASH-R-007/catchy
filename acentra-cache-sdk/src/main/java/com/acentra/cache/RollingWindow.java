package com.acentra.cache;

import java.util.Arrays;

/** Ring of 60 x 10-second buckets => counts for the last 10 minutes. Not thread-safe (cache lock). */
final class RollingWindow {
    static final int HITS = 0, MISSES = 1, PUTS = 2, EVICTIONS = 3, EXPIRATIONS = 4;
    static final int MINUTES = 10;
    private static final int KINDS = 5;
    private static final int BUCKETS = 60;
    private static final long BUCKET_MS = 10_000;

    private final long[][] counts = new long[KINDS][BUCKETS];
    private final long[] ids = new long[BUCKETS];

    RollingWindow() {
        Arrays.fill(ids, -1);
    }

    void add(int kind, long now) {
        long id = Math.floorDiv(now, BUCKET_MS);
        int idx = (int) Math.floorMod(id, (long) BUCKETS);
        if (ids[idx] != id) {
            ids[idx] = id;
            for (int k = 0; k < KINDS; k++) counts[k][idx] = 0;
        }
        counts[kind][idx]++;
    }

    long sum(int kind, long now) {
        long nowId = Math.floorDiv(now, BUCKET_MS);
        long total = 0;
        for (int i = 0; i < BUCKETS; i++) {
            if (ids[i] > nowId - BUCKETS && ids[i] <= nowId) total += counts[kind][i];
        }
        return total;
    }
}

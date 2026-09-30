package com.acentra.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Policy Arena engine: replays the region's read stream (key hashes only, never raw keys) through a shadow LRU and a
 * shadow LFU cache of the same effective capacity with read-through semantics (miss => load => insert with region TTL).
 * Hit rates are computed over the most recent {@code window} reads. Simulated, not measured.
 * Not thread-safe (cache lock).
 */
final class ShadowSimulator {

    record Stats(long requests, int windowRequests, double lruHitRate, double lfuHitRate, double topKeyConcentrationPercent) {}

    private final int window;
    private final long[] ringKeys;
    private final boolean[] ringLru;
    private final boolean[] ringLfu;
    private int pos;
    private int filled;
    private long total;
    private int lruWindowHits;
    private int lfuWindowHits;
    private final Map<Long, Integer> windowCounts = new HashMap<>();

    private final LinkedHashMap<Long, Long> lru = new LinkedHashMap<>(16, 0.75f, true);

    private static final class LfuEntry {
        long freq;
        long expiresAt;
    }

    private final Map<Long, LfuEntry> lfuMap = new HashMap<>();
    private final TreeMap<Long, LinkedHashSet<Long>> lfuBuckets = new TreeMap<>();

    ShadowSimulator(int window) {
        this.window = window;
        this.ringKeys = new long[window];
        this.ringLru = new boolean[window];
        this.ringLfu = new boolean[window];
    }

    void record(long keyHash, long now, int capacity, long ttlMs) {
        boolean lruHit = accessLru(keyHash, now, capacity, ttlMs);
        boolean lfuHit = accessLfu(keyHash, now, capacity, ttlMs);
        total++;
        if (filled == window) {
            long old = ringKeys[pos];
            windowCounts.merge(old, -1, (a, b) -> a + b == 0 ? null : a + b);
            if (ringLru[pos]) lruWindowHits--;
            if (ringLfu[pos]) lfuWindowHits--;
        } else {
            filled++;
        }
        ringKeys[pos] = keyHash;
        ringLru[pos] = lruHit;
        ringLfu[pos] = lfuHit;
        if (lruHit) lruWindowHits++;
        if (lfuHit) lfuWindowHits++;
        windowCounts.merge(keyHash, 1, Integer::sum);
        pos = (pos + 1) % window;
    }

    private boolean accessLru(long h, long now, int cap, long ttl) {
        Long exp = lru.get(h);
        boolean hit = exp != null && exp > now;
        if (!hit) {
            lru.remove(h);
            lru.put(h, now + ttl);
        }
        while (lru.size() > cap) {
            Iterator<Long> it = lru.keySet().iterator();
            it.next();
            it.remove();
        }
        return hit;
    }

    private boolean accessLfu(long h, long now, int cap, long ttl) {
        LfuEntry e = lfuMap.get(h);
        boolean hit = e != null && e.expiresAt > now;
        if (hit) {
            removeFromBucket(h, e.freq);
            e.freq++;
            lfuBuckets.computeIfAbsent(e.freq, k -> new LinkedHashSet<>()).add(h);
        } else {
            if (e != null) {
                removeFromBucket(h, e.freq);
                lfuMap.remove(h);
            }
            while (lfuMap.size() >= cap && !lfuMap.isEmpty()) evictLfu();
            LfuEntry n = new LfuEntry();
            n.freq = 0;
            n.expiresAt = now + ttl;
            lfuMap.put(h, n);
            lfuBuckets.computeIfAbsent(0L, k -> new LinkedHashSet<>()).add(h);
        }
        while (lfuMap.size() > cap) evictLfu();
        return hit;
    }

    private void evictLfu() {
        var first = lfuBuckets.firstEntry();
        if (first == null) return;
        Long victim = first.getValue().iterator().next();
        removeFromBucket(victim, first.getKey());
        lfuMap.remove(victim);
    }

    private void removeFromBucket(long h, long freq) {
        LinkedHashSet<Long> set = lfuBuckets.get(freq);
        if (set == null) return;
        set.remove(h);
        if (set.isEmpty()) lfuBuckets.remove(freq);
    }

    Stats stats() {
        if (filled == 0) return new Stats(total, 0, 0, 0, 0);
        double lruRate = Humanize.round2((double) lruWindowHits / filled * 100.0);
        double lfuRate = Humanize.round2((double) lfuWindowHits / filled * 100.0);
        List<Integer> counts = new ArrayList<>(windowCounts.values());
        counts.sort((a, b) -> Integer.compare(b, a));
        int topN = Math.max(1, (int) Math.ceil(counts.size() * 0.2));
        long top = 0;
        for (int i = 0; i < topN && i < counts.size(); i++) top += counts.get(i);
        double concentration = Humanize.round2((double) top / filled * 100.0);
        return new Stats(total, filled, lruRate, lfuRate, concentration);
    }
}

package com.acentra.cache;

import java.util.TreeMap;

/**
 * Frequency buckets. Within a bucket entries are in access order (head = least recently used), so the LFU victim
 * is the head of the lowest-frequency bucket: lowest frequency first, ties broken by least-recently-used.
 */
final class LfuIndex<V> {
    private static final class Bucket<V> {
        CacheEntry<V> head;
        CacheEntry<V> tail;
    }

    private final TreeMap<Long, Bucket<V>> buckets = new TreeMap<>();

    void add(CacheEntry<V> e) {
        Bucket<V> b = buckets.computeIfAbsent(e.frequency, k -> new Bucket<>());
        e.lfuPrev = b.tail;
        e.lfuNext = null;
        if (b.tail == null) b.head = e; else b.tail.lfuNext = e;
        b.tail = e;
    }

    void remove(CacheEntry<V> e) {
        Bucket<V> b = buckets.get(e.frequency);
        if (b == null) return;
        if (e.lfuPrev == null) b.head = e.lfuNext; else e.lfuPrev.lfuNext = e.lfuNext;
        if (e.lfuNext == null) b.tail = e.lfuPrev; else e.lfuNext.lfuPrev = e.lfuPrev;
        e.lfuPrev = null;
        e.lfuNext = null;
        if (b.head == null) buckets.remove(e.frequency);
    }

    /** Record a valid hit: move to the next frequency bucket (tail = most recent). */
    void touch(CacheEntry<V> e) {
        remove(e);
        e.frequency++;
        add(e);
    }

    CacheEntry<V> victim() {
        var first = buckets.firstEntry();
        return first == null ? null : first.getValue().head;
    }

    void clear() {
        buckets.clear();
    }
}

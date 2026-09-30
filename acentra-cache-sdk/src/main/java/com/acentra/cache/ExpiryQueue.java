package com.acentra.cache;

import java.util.Collection;
import java.util.PriorityQueue;

/**
 * Min-heap by expiry time with lazy deletion: nodes of entries that were removed/overwritten stay in the heap and are
 * skipped ({@code !entry.live}). Makes "clean expired first" O(log n) amortised instead of O(n) per put.
 */
final class ExpiryQueue {
    private record Node(long expiresAt, CacheEntry<?> entry) {}

    private final PriorityQueue<Node> heap = new PriorityQueue<>((a, b) -> Long.compare(a.expiresAt, b.expiresAt));

    void add(CacheEntry<?> e) {
        heap.add(new Node(e.expiresAt, e));
    }

    /** @return next entry whose expiry time has passed (may be a dead node), or null. */
    @SuppressWarnings("unchecked")
    <V> CacheEntry<V> pollExpired(long now) {
        Node n = heap.peek();
        if (n == null || n.expiresAt > now) return null;
        heap.poll();
        return (CacheEntry<V>) n.entry;
    }

    void maybeCompact(Collection<? extends CacheEntry<?>> liveL1, Collection<? extends CacheEntry<?>> liveVictim) {
        int live = liveL1.size() + liveVictim.size();
        if (heap.size() <= 2L * live + 256) return;
        heap.clear();
        for (CacheEntry<?> e : liveL1) add(e);
        for (CacheEntry<?> e : liveVictim) add(e);
    }

    void clear() {
        heap.clear();
    }
}

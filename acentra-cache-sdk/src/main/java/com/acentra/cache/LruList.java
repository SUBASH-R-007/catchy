package com.acentra.cache;

/** Intrusive doubly-linked access-order list. head = least recently used. Not thread-safe (cache lock). */
final class LruList<V> {
    CacheEntry<V> head;
    CacheEntry<V> tail;

    void addLast(CacheEntry<V> e) {
        e.lruPrev = tail;
        e.lruNext = null;
        if (tail == null) head = e; else tail.lruNext = e;
        tail = e;
    }

    void remove(CacheEntry<V> e) {
        if (e.lruPrev == null) head = e.lruNext; else e.lruPrev.lruNext = e.lruNext;
        if (e.lruNext == null) tail = e.lruPrev; else e.lruNext.lruPrev = e.lruPrev;
        e.lruPrev = null;
        e.lruNext = null;
    }

    void touch(CacheEntry<V> e) {
        if (tail == e) return;
        remove(e);
        addLast(e);
    }

    CacheEntry<V> first() {
        return head;
    }

    void clear() {
        head = null;
        tail = null;
    }
}

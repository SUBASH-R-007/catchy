package com.acentra.cache;

/** Eviction strategy applied only when capacity (entries or estimated memory) must be reclaimed. TTL is independent. */
public enum EvictionPolicy {
    LRU,
    LFU
}

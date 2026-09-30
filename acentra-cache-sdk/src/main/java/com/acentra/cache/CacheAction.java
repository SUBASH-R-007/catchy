package com.acentra.cache;

/** What a {@link CacheDecisionEvent} describes (Eviction X-ray). */
public enum CacheAction {
    HIT,
    MISS,
    PUT,
    REMOVE,
    CLEAR,
    EXPIRED,
    EVICTED,
    MEMORY_EVICTED,
    ENTRY_LIMIT_EVICTED,
    POLICY_CHANGED,
    CLEANUP,
    POLICY_RECOMMENDATION,
    VICTIM_HIT,
    STALE_SERVED,
    REFRESH_STARTED,
    REFRESH_COALESCED,
    REFRESH_FAILED,
    SOURCE_VALIDATED,
    CONFIG_CHANGED;

    /** Routine actions are sampled to keep telemetry and the local log useful. */
    public boolean isRoutine() {
        return this == HIT || this == PUT;
    }
}

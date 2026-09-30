package com.acentra.cache;

/** Business risk of serving stale data from a region. HIGH/CRITICAL regions never serve stale values. */
public enum CacheRiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL;

    public boolean isElevated() {
        return this == HIGH || this == CRITICAL;
    }
}

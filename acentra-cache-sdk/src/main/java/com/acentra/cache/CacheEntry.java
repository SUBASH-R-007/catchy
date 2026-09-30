package com.acentra.cache;

/**
 * One cached value with its own TTL and eviction metadata. Instances handed out by the public API
 * ({@link AcentraCache#peekEntry}) are immutable copies; the live internal entry is never exposed.
 * The raw key is intentionally not part of this type, only a fingerprint.
 */
public final class CacheEntry<V> {
    // Internal live state, guarded by the owning cache's lock.
    Object key;
    V value;
    long createdAt;
    long expiresAt;
    long frequency;
    long lastAccessTime;
    long estimatedSizeBytes;
    CacheRiskLevel riskLevel;
    String cacheRegion;
    String sourceVersion;
    String keyFingerprint;
    boolean live;
    boolean inVictim;
    CacheEntry<V> lruPrev, lruNext, lfuPrev, lfuNext;

    CacheEntry() {}

    /** Immutable snapshot copy for the public API. */
    CacheEntry<V> copy() {
        CacheEntry<V> c = new CacheEntry<>();
        c.value = value;
        c.createdAt = createdAt;
        c.expiresAt = expiresAt;
        c.frequency = frequency;
        c.lastAccessTime = lastAccessTime;
        c.estimatedSizeBytes = estimatedSizeBytes;
        c.riskLevel = riskLevel;
        c.cacheRegion = cacheRegion;
        c.sourceVersion = sourceVersion;
        c.keyFingerprint = keyFingerprint;
        return c;
    }

    public V getValue() { return value; }
    public long getCreatedAt() { return createdAt; }
    public long getExpiresAt() { return expiresAt; }
    public long getFrequency() { return frequency; }
    public long getLastAccessTime() { return lastAccessTime; }
    public long getEstimatedSizeBytes() { return estimatedSizeBytes; }
    public CacheRiskLevel getRiskLevel() { return riskLevel; }
    public String getCacheRegion() { return cacheRegion; }
    public String getSourceVersion() { return sourceVersion; }
    public String getKeyFingerprint() { return keyFingerprint; }

    public boolean isExpiredAt(long nowMillis) {
        return expiresAt <= nowMillis;
    }

    public long remainingTtlMillis(long nowMillis) {
        return Math.max(0, expiresAt - nowMillis);
    }
}

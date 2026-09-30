package com.acentra.cache;

import com.acentra.cache.telemetry.TelemetryClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable configuration of one cache region (a business function such as {@code claim-rules}). */
public final class CacheRegionConfig {
    private static final Pattern REGION = Pattern.compile("^[a-z0-9][a-z0-9-]{0,62}$");

    private final String regionName;
    private final String applicationName;
    private final String environment;
    private final int maximumEntries;
    private final long maximumMemoryBytes;
    private final EvictionPolicy defaultPolicy;
    private final Duration defaultTtl;
    private final CacheRiskLevel riskLevel;
    private final int decisionEventCapacity;
    private final int routineEventSampling;
    private final boolean victimCacheEnabled;
    private final double victimCacheFraction;
    private final boolean stampedeShieldEnabled;
    private final boolean staleWhileRevalidate;
    private final Duration staleGrace;
    private final Duration loadWaitTimeout;
    private final boolean shadowEnabled;
    private final int shadowWindowSize;
    private final CacheKeySanitizer keySanitizer;
    private final CacheMemoryEstimator memoryEstimator;
    private final TelemetryClient telemetryClient;
    private final Clock clock;

    private CacheRegionConfig(Builder b) {
        this.regionName = b.regionName;
        this.applicationName = b.applicationName;
        this.environment = b.environment;
        this.maximumEntries = b.maximumEntries;
        this.maximumMemoryBytes = b.maximumMemoryBytes;
        this.defaultPolicy = b.defaultPolicy;
        this.defaultTtl = b.defaultTtl;
        this.riskLevel = b.riskLevel;
        this.decisionEventCapacity = b.decisionEventCapacity;
        this.routineEventSampling = b.routineEventSampling;
        this.victimCacheEnabled = b.victimCacheEnabled;
        this.victimCacheFraction = b.victimCacheFraction;
        this.stampedeShieldEnabled = b.stampedeShieldEnabled;
        this.staleWhileRevalidate = b.staleWhileRevalidate;
        this.staleGrace = b.staleGrace;
        this.loadWaitTimeout = b.loadWaitTimeout;
        this.shadowEnabled = b.shadowEnabled;
        this.shadowWindowSize = b.shadowWindowSize;
        this.keySanitizer = b.keySanitizer;
        this.memoryEstimator = b.memoryEstimator;
        this.telemetryClient = b.telemetryClient;
        this.clock = b.clock;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.regionName = regionName;
        b.applicationName = applicationName;
        b.environment = environment;
        b.maximumEntries = maximumEntries;
        b.maximumMemoryBytes = maximumMemoryBytes;
        b.defaultPolicy = defaultPolicy;
        b.defaultTtl = defaultTtl;
        b.riskLevel = riskLevel;
        b.decisionEventCapacity = decisionEventCapacity;
        b.routineEventSampling = routineEventSampling;
        b.victimCacheEnabled = victimCacheEnabled;
        b.victimCacheFraction = victimCacheFraction;
        b.stampedeShieldEnabled = stampedeShieldEnabled;
        b.staleWhileRevalidate = staleWhileRevalidate;
        b.staleGrace = staleGrace;
        b.loadWaitTimeout = loadWaitTimeout;
        b.shadowEnabled = shadowEnabled;
        b.shadowWindowSize = shadowWindowSize;
        b.keySanitizer = keySanitizer;
        b.memoryEstimator = memoryEstimator;
        b.telemetryClient = telemetryClient;
        b.clock = clock;
        return b;
    }

    public String regionName() { return regionName; }
    public String applicationName() { return applicationName; }
    public String environment() { return environment; }
    public int maximumEntries() { return maximumEntries; }
    public long maximumMemoryBytes() { return maximumMemoryBytes; }
    public EvictionPolicy defaultPolicy() { return defaultPolicy; }
    public Duration defaultTtl() { return defaultTtl; }
    public CacheRiskLevel riskLevel() { return riskLevel; }
    public int decisionEventCapacity() { return decisionEventCapacity; }
    public int routineEventSampling() { return routineEventSampling; }
    public boolean victimCacheEnabled() { return victimCacheEnabled; }
    public double victimCacheFraction() { return victimCacheFraction; }
    public boolean stampedeShieldEnabled() { return stampedeShieldEnabled; }
    public boolean staleWhileRevalidate() { return staleWhileRevalidate; }
    public Duration staleGrace() { return staleGrace; }
    public Duration loadWaitTimeout() { return loadWaitTimeout; }
    public boolean shadowEnabled() { return shadowEnabled; }
    public int shadowWindowSize() { return shadowWindowSize; }
    public CacheKeySanitizer keySanitizer() { return keySanitizer; }
    public CacheMemoryEstimator memoryEstimator() { return memoryEstimator; }
    public TelemetryClient telemetryClient() { return telemetryClient; }
    public Clock clock() { return clock; }

    public static final class Builder {
        private String regionName;
        private String applicationName = "local-app";
        private String environment = "local";
        private int maximumEntries = 1_000;
        private long maximumMemoryBytes = 64L * 1024 * 1024;
        private EvictionPolicy defaultPolicy = EvictionPolicy.LRU;
        private Duration defaultTtl = Duration.ofMinutes(5);
        private CacheRiskLevel riskLevel = CacheRiskLevel.LOW;
        private int decisionEventCapacity = 500;
        private int routineEventSampling = 10;
        private boolean victimCacheEnabled = false;
        private double victimCacheFraction = 0.15;
        private boolean stampedeShieldEnabled = true;
        private boolean staleWhileRevalidate = false;
        private Duration staleGrace = Duration.ofSeconds(30);
        private Duration loadWaitTimeout = Duration.ofSeconds(30);
        private boolean shadowEnabled = true;
        private int shadowWindowSize = 1_000;
        private CacheKeySanitizer keySanitizer;
        private CacheMemoryEstimator memoryEstimator;
        private TelemetryClient telemetryClient;
        private Clock clock;

        private Builder() {}

        public Builder regionName(String v) { this.regionName = v; return this; }
        public Builder applicationName(String v) { this.applicationName = v; return this; }
        public Builder environment(String v) { this.environment = v; return this; }
        public Builder maximumEntries(int v) { this.maximumEntries = v; return this; }
        public Builder maximumMemoryBytes(long v) { this.maximumMemoryBytes = v; return this; }
        public Builder defaultPolicy(EvictionPolicy v) { this.defaultPolicy = v; return this; }
        public Builder defaultTtl(Duration v) { this.defaultTtl = v; return this; }
        public Builder riskLevel(CacheRiskLevel v) { this.riskLevel = v; return this; }
        public Builder decisionEventCapacity(int v) { this.decisionEventCapacity = v; return this; }
        /** Record 1 of every N routine (HIT/PUT) events locally and in telemetry. 1 = every event. */
        public Builder routineEventSampling(int v) { this.routineEventSampling = v; return this; }
        public Builder victimCacheEnabled(boolean v) { this.victimCacheEnabled = v; return this; }
        public Builder victimCacheFraction(double v) { this.victimCacheFraction = v; return this; }
        public Builder stampedeShieldEnabled(boolean v) { this.stampedeShieldEnabled = v; return this; }
        /** Serve a just-expired value while one refresh runs. Only permitted for LOW-risk regions. */
        public Builder staleWhileRevalidate(boolean v) { this.staleWhileRevalidate = v; return this; }
        public Builder staleGrace(Duration v) { this.staleGrace = v; return this; }
        public Builder loadWaitTimeout(Duration v) { this.loadWaitTimeout = v; return this; }
        public Builder shadowEnabled(boolean v) { this.shadowEnabled = v; return this; }
        public Builder shadowWindowSize(int v) { this.shadowWindowSize = v; return this; }
        public Builder keySanitizer(CacheKeySanitizer v) { this.keySanitizer = v; return this; }
        public Builder memoryEstimator(CacheMemoryEstimator v) { this.memoryEstimator = v; return this; }
        public Builder telemetryClient(TelemetryClient v) { this.telemetryClient = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }

        public CacheRegionConfig build() {
            if (regionName == null || !REGION.matcher(regionName).matches()) {
                throw new IllegalArgumentException("regionName must match " + REGION.pattern());
            }
            Objects.requireNonNull(applicationName, "applicationName");
            Objects.requireNonNull(environment, "environment");
            if (maximumEntries < 1) throw new IllegalArgumentException("maximumEntries must be >= 1");
            if (maximumMemoryBytes < 1) throw new IllegalArgumentException("maximumMemoryBytes must be >= 1");
            Objects.requireNonNull(defaultPolicy, "defaultPolicy");
            Objects.requireNonNull(defaultTtl, "defaultTtl");
            if (defaultTtl.isZero() || defaultTtl.isNegative()) throw new IllegalArgumentException("defaultTtl must be positive");
            Objects.requireNonNull(riskLevel, "riskLevel");
            if (decisionEventCapacity < 1) throw new IllegalArgumentException("decisionEventCapacity must be >= 1");
            if (routineEventSampling < 1) throw new IllegalArgumentException("routineEventSampling must be >= 1");
            if (victimCacheFraction <= 0 || victimCacheFraction > 0.5) {
                throw new IllegalArgumentException("victimCacheFraction must be in (0, 0.5]");
            }
            if (staleWhileRevalidate && riskLevel != CacheRiskLevel.LOW) {
                throw new IllegalArgumentException(
                        "staleWhileRevalidate is only allowed for LOW-risk regions (region risk is " + riskLevel + ")");
            }
            if (staleGrace.isNegative()) throw new IllegalArgumentException("staleGrace must not be negative");
            if (shadowWindowSize < 10) throw new IllegalArgumentException("shadowWindowSize must be >= 10");
            if (keySanitizer == null) keySanitizer = CacheKeySanitizer.withRandomSalt();
            if (memoryEstimator == null) memoryEstimator = CacheMemoryEstimator.standard();
            if (telemetryClient == null) telemetryClient = TelemetryClient.noop();
            if (clock == null) clock = Clock.systemUTC();
            return new CacheRegionConfig(this);
        }
    }
}

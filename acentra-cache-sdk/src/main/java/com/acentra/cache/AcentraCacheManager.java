package com.acentra.cache;

import com.acentra.cache.telemetry.BatchingHttpTelemetryClient;
import com.acentra.cache.telemetry.ControlResponse;
import com.acentra.cache.telemetry.TelemetryClient;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * One per application. Creates regions, shares the key sanitizer and the telemetry client, periodically cleans
 * expired entries, publishes region snapshots, and applies engineer-approved policy / tuning decisions polled from
 * the telemetry service. Nothing here ever changes a policy on its own.
 */
public final class AcentraCacheManager implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(AcentraCacheManager.class.getName());

    public static final class Builder {
        private String applicationName;
        private String environment = "local";
        private String instanceId;
        private String keySalt;
        private CacheKeySanitizer.Mode keyMode = CacheKeySanitizer.Mode.HASHED;
        private String telemetryEndpoint;
        private String telemetryApiKey;
        private Duration flushInterval = Duration.ofSeconds(5);
        private Duration snapshotInterval = Duration.ofSeconds(2);
        private Duration cleanupInterval = Duration.ofSeconds(5);
        private int batchSize = 50;
        private boolean controlPolling = true;
        private Clock clock;
        private TelemetryClient telemetryClient;

        private Builder() {}

        public Builder applicationName(String v) { this.applicationName = v; return this; }
        public Builder environment(String v) { this.environment = v; return this; }
        public Builder instanceId(String v) { this.instanceId = v; return this; }
        /** Application salt for key fingerprints. Keep it secret and out of source control. */
        public Builder keySalt(String v) { this.keySalt = v; return this; }
        public Builder keyMode(CacheKeySanitizer.Mode v) { this.keyMode = v; return this; }
        public Builder telemetryEndpoint(String v) { this.telemetryEndpoint = v; return this; }
        public Builder telemetryApiKey(String v) { this.telemetryApiKey = v; return this; }
        public Builder flushInterval(Duration v) { this.flushInterval = v; return this; }
        public Builder snapshotInterval(Duration v) { this.snapshotInterval = v; return this; }
        public Builder cleanupInterval(Duration v) { this.cleanupInterval = v; return this; }
        public Builder batchSize(int v) { this.batchSize = v; return this; }
        public Builder controlPolling(boolean v) { this.controlPolling = v; return this; }
        public Builder clock(Clock v) { this.clock = v; return this; }
        /** Supply your own client (tests, in-process ingestion). Overrides endpoint/apiKey. */
        public Builder telemetryClient(TelemetryClient v) { this.telemetryClient = v; return this; }

        public AcentraCacheManager build() {
            Objects.requireNonNull(applicationName, "applicationName");
            return new AcentraCacheManager(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String applicationName;
    private final String environment;
    private final CacheKeySanitizer sanitizer;
    private final TelemetryClient telemetry;
    private final boolean ownsTelemetry;
    private final Clock clock;
    private final Map<String, AcentraCache<?, ?>> caches = new ConcurrentHashMap<>();
    private final Map<String, Long> appliedTuningVersion = new ConcurrentHashMap<>();
    private final Map<String, Long> appliedPolicyRequest = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;

    private AcentraCacheManager(Builder b) {
        this.applicationName = b.applicationName;
        this.environment = b.environment;
        this.clock = b.clock;
        this.sanitizer = b.keySalt == null && b.keyMode == CacheKeySanitizer.Mode.HASHED
                ? CacheKeySanitizer.withRandomSalt()
                : new CacheKeySanitizer(b.keySalt, b.keyMode);
        if (b.telemetryClient != null) {
            this.telemetry = b.telemetryClient;
            this.ownsTelemetry = false;
        } else if (b.telemetryEndpoint != null && b.telemetryApiKey != null && !b.telemetryApiKey.isBlank()) {
            BatchingHttpTelemetryClient.Config c = BatchingHttpTelemetryClient.config()
                    .endpoint(b.telemetryEndpoint)
                    .apiKey(b.telemetryApiKey)
                    .applicationName(b.applicationName)
                    .environment(b.environment)
                    .flushInterval(b.flushInterval)
                    .batchSize(b.batchSize)
                    .controlPollingEnabled(b.controlPolling);
            if (b.instanceId != null) c.instanceId(b.instanceId);
            this.telemetry = new BatchingHttpTelemetryClient(c);
            this.ownsTelemetry = true;
        } else {
            this.telemetry = TelemetryClient.noop();
            this.ownsTelemetry = false;
            LOG.log(System.Logger.Level.INFO, "No telemetry endpoint/API key configured for {0}; telemetry disabled.", applicationName);
        }
        this.telemetry.setControlListener(this::applyControl);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "acentra-cache-manager");
            t.setDaemon(true);
            return t;
        });
        long cleanup = Math.max(50, b.cleanupInterval.toMillis());
        long snap = Math.max(50, b.snapshotInterval.toMillis());
        scheduler.scheduleWithFixedDelay(this::cleanupAll, cleanup, cleanup, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::publishAll, snap, snap, TimeUnit.MILLISECONDS);
    }

    /** Creates and registers a region. Application name, environment, sanitizer and telemetry are filled in. */
    public <K, V> AcentraCache<K, V> cache(CacheRegionConfig.Builder region) {
        region.applicationName(applicationName).environment(environment).keySanitizer(sanitizer).telemetryClient(telemetry);
        if (clock != null) region.clock(clock);
        CacheRegionConfig cfg = region.build();
        AcentraCache<K, V> cache = new AcentraCache<>(cfg);
        if (caches.putIfAbsent(cfg.regionName(), cache) != null) {
            throw new IllegalStateException("Cache region already registered: " + cfg.regionName());
        }
        scheduler.execute(() -> telemetry.publishSnapshot(cache.snapshot()));
        return cache;
    }

    public Collection<AcentraCache<?, ?>> caches() {
        return Collections.unmodifiableCollection(caches.values());
    }

    public TelemetryClient telemetry() {
        return telemetry;
    }

    public String applicationName() {
        return applicationName;
    }

    public String environment() {
        return environment;
    }

    private void cleanupAll() {
        for (AcentraCache<?, ?> c : caches.values()) {
            try {
                c.cleanUp();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Expired-entry cleanup failed: {0}", e.getClass().getSimpleName());
            }
        }
    }

    private void publishAll() {
        for (AcentraCache<?, ?> c : caches.values()) {
            try {
                telemetry.publishSnapshot(c.snapshot());
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Snapshot failed: {0}", e.getClass().getSimpleName());
            }
        }
    }

    void applyControl(ControlResponse response) {
        if (response == null || response.regions() == null) return;
        for (ControlResponse.RegionControl rc : response.regions()) {
            AcentraCache<?, ?> cache = caches.get(rc.cacheRegion());
            if (cache == null) continue;
            try {
                Long reqId = rc.policyRequestId();
                if (rc.desiredPolicy() != null && reqId != null
                        && reqId > appliedPolicyRequest.getOrDefault(rc.cacheRegion(), 0L)) {
                    appliedPolicyRequest.put(rc.cacheRegion(), reqId);
                    cache.changePolicy(rc.desiredPolicy(), "Applied engineer-approved policy change request #" + reqId);
                }
                ControlResponse.Tuning t = rc.tuning();
                Long ver = rc.tuningVersion();
                if (t != null && ver != null && ver > appliedTuningVersion.getOrDefault(rc.cacheRegion(), 0L)) {
                    appliedTuningVersion.put(rc.cacheRegion(), ver);
                    Integer entries = t.maximumEntries() == null ? null : (int) Math.min(Integer.MAX_VALUE, t.maximumEntries());
                    Duration ttl = t.defaultTtlMs() == null ? null : Duration.ofMillis(t.defaultTtlMs());
                    cache.reconfigure(entries, t.maximumMemoryBytes(), ttl, "Applied admin configuration change v" + ver);
                }
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "Could not apply control directive for region {0}: {1}",
                        rc.cacheRegion(), e.getClass().getSimpleName());
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        try {
            publishAll();
        } finally {
            if (ownsTelemetry) telemetry.close();
        }
    }
}

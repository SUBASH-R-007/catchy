package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.catchy.demo.eligibility.EligibilityModels.AuthorizationDecision;
import com.acentra.catchy.demo.eligibility.EligibilityModels.EligibilityResult;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One {@link AcentraCacheManager} per application and the two cache regions of the eligibility demo. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoProperties.class)
public class CacheConfig {
    private static final Logger LOG = LoggerFactory.getLogger(CacheConfig.class);

    public static final String APPLICATION = "eligibility-service";
    public static final String ELIGIBILITY_REGION = "eligibility-summary";
    public static final String AUTHORIZATION_REGION = "authorization-decision";

    /** Modeled in-cache footprint of ONE eligibility entry (see {@link FixedFootprintEstimator}). */
    static final long ELIGIBILITY_ENTRY_FOOTPRINT_BYTES = 4 * 1024;

    /**
     * Telemetry is enabled only when an API key is configured. A {@link TelemetryClient} bean, if present, wins
     * (used by tests to capture exactly what would be sent). The manager is closed on shutdown.
     */
    @Bean(destroyMethod = "close")
    AcentraCacheManager acentraCacheManager(DemoProperties props, @Value("${server.port:8092}") int port,
                                            ObjectProvider<TelemetryClient> telemetryOverride) {
        AcentraCacheManager.Builder b = AcentraCacheManager.builder()
                .applicationName(APPLICATION)
                .environment(props.getEnvironment())
                .instanceId(APPLICATION + "-" + port)
                .snapshotInterval(Duration.ofSeconds(2))
                .flushInterval(Duration.ofSeconds(2))
                .cleanupInterval(Duration.ofSeconds(2));
        if (props.getKeySalt() != null && !props.getKeySalt().isBlank()) {
            b.keySalt(props.getKeySalt());
        }
        TelemetryClient override = telemetryOverride.getIfAvailable();
        if (override != null) {
            b.telemetryClient(override);
        } else if (props.getTelemetry().isEnabled()) {
            b.telemetryEndpoint(props.getTelemetry().getUrl()).telemetryApiKey(props.getTelemetry().getApiKey());
            LOG.info("Telemetry enabled for {} (endpoint {}, environment {}).", APPLICATION, props.getTelemetry().getUrl(),
                    props.getEnvironment());
        } else {
            LOG.info("CATCHY_ELIGIBILITY_API_KEY is not set: telemetry is disabled for {}. The service still works normally.",
                    APPLICATION);
        }
        return b.build();
    }

    /**
     * HIGH risk: minimal derived results only, hashed keys (see {@link MemberKeyHasher}), short TTL and NO
     * stale-while-revalidate. The memory limit is what binds under churn: 512 KB / 4 KB modeled footprint = 128 entries.
     */
    @Bean
    AcentraCache<String, EligibilityResult> eligibilitySummaryCache(AcentraCacheManager manager) {
        return manager.cache(CacheRegionConfig.builder()
                .regionName(ELIGIBILITY_REGION)
                .defaultPolicy(EvictionPolicy.LRU)
                .riskLevel(CacheRiskLevel.HIGH)
                .defaultTtl(Duration.ofMinutes(2))
                .maximumEntries(150)
                .maximumMemoryBytes(512L * 1024L)
                .memoryEstimator(new FixedFootprintEstimator(ELIGIBILITY_ENTRY_FOOTPRINT_BYTES))
                .staleWhileRevalidate(false)
                .routineEventSampling(20));
    }

    /**
     * CRITICAL risk: the cache may support preliminary display only. A final action always revalidates against the
     * source ({@code requireFreshFromSource}). Small capacity, 60 second TTL, never stale.
     */
    @Bean
    AcentraCache<String, AuthorizationDecision> authorizationDecisionCache(AcentraCacheManager manager) {
        return manager.cache(CacheRegionConfig.builder()
                .regionName(AUTHORIZATION_REGION)
                .defaultPolicy(EvictionPolicy.LRU)
                .riskLevel(CacheRiskLevel.CRITICAL)
                .defaultTtl(Duration.ofSeconds(60))
                .maximumEntries(50)
                .maximumMemoryBytes(256L * 1024L)
                .staleWhileRevalidate(false)
                .routineEventSampling(5));
    }
}

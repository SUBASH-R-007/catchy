package com.acentra.catchy.demo.claims;

import com.acentra.cache.AcentraCache;
import com.acentra.cache.AcentraCacheManager;
import com.acentra.cache.CacheRegionConfig;
import com.acentra.cache.CacheRiskLevel;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryClient;
import com.acentra.catchy.demo.claims.ClaimsModels.ProviderGroupSummary;
import com.acentra.catchy.demo.claims.ClaimsModels.RuleSet;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** One {@link AcentraCacheManager} per application and the two cache regions of the claims demo. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DemoProperties.class)
public class CacheConfig {
    private static final Logger LOG = LoggerFactory.getLogger(CacheConfig.class);

    public static final String APPLICATION = "claims-service";
    public static final String RULES_REGION = "claim-rules";
    public static final String PROVIDERS_REGION = "provider-directory";

    /**
     * Telemetry is enabled only when an API key is configured. A {@link TelemetryClient} bean, if present, wins
     * (used by tests to capture exactly what would be sent). The manager is closed on shutdown.
     */
    @Bean(destroyMethod = "close")
    AcentraCacheManager acentraCacheManager(DemoProperties props, @Value("${server.port:8091}") int port,
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
            LOG.info("CATCHY_CLAIMS_API_KEY is not set: telemetry is disabled for {}. The service still works normally.",
                    APPLICATION);
        }
        return b.build();
    }

    /** Synthetic, non-patient-specific rule bundles. LFU + small capacity so workloads visibly evict. */
    @Bean
    AcentraCache<String, RuleSet> claimRulesCache(AcentraCacheManager manager) {
        return manager.cache(CacheRegionConfig.builder()
                .regionName(RULES_REGION)
                .defaultPolicy(EvictionPolicy.LFU)
                .riskLevel(CacheRiskLevel.MEDIUM)
                .defaultTtl(Duration.ofMinutes(15))
                .maximumEntries(40)
                .maximumMemoryBytes(1024L * 1024L)
                .victimCacheEnabled(true)
                .routineEventSampling(10));
    }

    /** Low-risk directory data: LRU, long TTL and stale-while-revalidate with a 30 second grace. */
    @Bean
    AcentraCache<String, ProviderGroupSummary> providerDirectoryCache(AcentraCacheManager manager) {
        return manager.cache(CacheRegionConfig.builder()
                .regionName(PROVIDERS_REGION)
                .defaultPolicy(EvictionPolicy.LRU)
                .riskLevel(CacheRiskLevel.LOW)
                .defaultTtl(Duration.ofHours(1))
                .maximumEntries(200)
                .maximumMemoryBytes(4L * 1024L * 1024L)
                .staleWhileRevalidate(true)
                .staleGrace(Duration.ofSeconds(30))
                .routineEventSampling(10));
    }
}

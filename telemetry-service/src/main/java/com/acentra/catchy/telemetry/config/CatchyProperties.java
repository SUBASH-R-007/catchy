package com.acentra.catchy.telemetry.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Typed view of the {@code catchy.*} configuration. Secrets only ever come from the environment. */
@ConfigurationProperties(prefix = "catchy")
public record CatchyProperties(
        @DefaultValue("false") boolean demoMode,
        @DefaultValue("http://localhost:5173") String dashboardOrigin,
        @DefaultValue Security security,
        @DefaultValue Ingest ingest,
        @DefaultValue Advisor advisor,
        @DefaultValue Ollama ollama,
        @DefaultValue Retention retention,
        @DefaultValue Metrics metrics,
        @DefaultValue Jobs jobs,
        @DefaultValue Seed seed) {

    public record Security(
            @DefaultValue("") String tokenSecret,
            @DefaultValue("PT8H") Duration tokenTtl,
            @DefaultValue("") String adminPassword,
            @DefaultValue("") String engineerPassword,
            @DefaultValue("") String viewerPassword) {}

    public record Ingest(
            @DefaultValue("1048576") int maxBodyBytes,
            @DefaultValue("65536") int maxOtherBodyBytes,
            @DefaultValue("500") int maxBatchEvents,
            @DefaultValue("200") int maxSnapshotsPerBatch,
            @DefaultValue("600") int rateLimitPerMinute) {}

    public record Advisor(
            @DefaultValue("100") long minRequests,
            @DefaultValue("5.0") double minImprovementPercent,
            @DefaultValue("PT5M") Duration cooldown,
            @DefaultValue("PT15S") Duration interval) {}

    public record Ollama(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("http://localhost:11434") String url,
            @DefaultValue("llama3.2") String model,
            @DefaultValue("PT3S") Duration timeout) {}

    public record Retention(
            @DefaultValue("20000") int eventsPerApplication,
            @DefaultValue("PT3H") Duration snapshotMaxAge,
            @DefaultValue("PT1M") Duration interval) {}

    public record Metrics(@DefaultValue("PT1H") Duration instanceStaleAfter) {}

    public record Jobs(@DefaultValue("true") boolean enabled) {}

    public record Seed(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String claimsApiKey,
            @DefaultValue("") String eligibilityApiKey) {}
}

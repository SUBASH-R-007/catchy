package com.acentra.catchy.telemetry.security;

/** An SDK instance identified by an application API key. The key's application is authoritative. */
public record IngestPrincipal(Long apiKeyId, String keyPrefix, Long applicationId, String applicationName, String environment) {}

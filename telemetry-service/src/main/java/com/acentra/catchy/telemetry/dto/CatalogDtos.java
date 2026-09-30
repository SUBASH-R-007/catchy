package com.acentra.catchy.telemetry.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Projects, applications and API keys (docs/api.md). */
public final class CatalogDtos {
    private CatalogDtos() {}

    public record CreateProjectRequest(
            @NotBlank @Size(min = 2, max = 80) String name,
            @Size(max = 500) String description) {}

    public record Project(Long id, String name, String description, Instant createdAt, long applicationCount) {}

    public record CreateApplicationRequest(
            @NotBlank @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,62}$", message = "must match ^[a-z0-9][a-z0-9-]{1,62}$") String name,
            @Size(max = 120) String displayName,
            @NotBlank @Pattern(regexp = "^[a-z][a-z0-9-]{1,30}$", message = "must match ^[a-z][a-z0-9-]{1,30}$") String environment,
            @Size(max = 500) String description) {}

    public record Application(
            Long id, Long projectId, String projectName, String name, String displayName, String environment,
            String description, Instant createdAt, Instant lastTelemetryAt, int regionCount) {}

    public record CreateApiKeyRequest(@Size(max = 120) String label) {}

    public record ApiKey(
            Long id, Long applicationId, String label, String keyPrefix, String maskedKey, Instant createdAt,
            Instant lastUsedAt, Instant revokedAt, boolean active) {}

    /** Returned exactly once, at creation: the only response that ever contains the plaintext key. */
    public record ApiKeyCreated(
            Long id, Long applicationId, String label, String keyPrefix, String maskedKey, String apiKey, Instant createdAt) {}
}

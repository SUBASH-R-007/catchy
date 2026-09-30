package com.acentra.catchy.telemetry.service;

import com.acentra.catchy.telemetry.config.Times;
import com.acentra.catchy.telemetry.domain.ApiKeyRepository;
import com.acentra.catchy.telemetry.domain.ApplicationApiKey;
import com.acentra.catchy.telemetry.domain.ApplicationRepository;
import com.acentra.catchy.telemetry.domain.ApplicationService;
import com.acentra.catchy.telemetry.dto.CatalogDtos.ApiKey;
import com.acentra.catchy.telemetry.dto.CatalogDtos.ApiKeyCreated;
import com.acentra.catchy.telemetry.security.ApiKeys;
import com.acentra.catchy.telemetry.security.IngestPrincipal;
import com.acentra.catchy.telemetry.web.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** API key lifecycle and authentication. Only the SHA-256 hash and a short prefix are ever persisted. */
@Service
public class ApiKeyService {

    private final ApiKeyRepository keys;
    private final ApplicationRepository applications;
    private final AuditService audit;
    private final Clock clock;

    public ApiKeyService(ApiKeyRepository keys, ApplicationRepository applications, AuditService audit, Clock clock) {
        this.keys = keys;
        this.applications = applications;
        this.audit = audit;
        this.clock = clock;
    }

    /** Resolves a presented key to its application; empty for malformed, unknown or revoked keys. */
    @Transactional
    public Optional<IngestPrincipal> authenticate(String presentedKey) {
        if (!ApiKeys.isWellFormed(presentedKey)) return Optional.empty();
        Optional<ApplicationApiKey> found = keys.findByKeyHash(ApiKeys.hash(presentedKey));
        if (found.isEmpty()) return Optional.empty();
        ApplicationApiKey key = found.get();
        if (!key.isActive() || !ApiKeys.matches(key.keyHash, presentedKey)) return Optional.empty();
        Optional<ApplicationService> app = applications.findById(key.applicationId);
        if (app.isEmpty()) return Optional.empty();
        keys.touch(key.id, Times.now(clock));
        return Optional.of(new IngestPrincipal(key.id, key.keyPrefix, app.get().id, app.get().name, app.get().environment));
    }

    @Transactional
    public ApiKeyCreated create(Long applicationId, String label) {
        ApplicationService app = requireApplication(applicationId);
        String plaintext = ApiKeys.generate();
        ApplicationApiKey row = register(app.id, label == null || label.isBlank() ? "API key" : label.trim(), plaintext);
        audit.success(AuditAction.API_KEY_CREATED, "API_KEY", String.valueOf(row.id), app.id,
                "Created key '" + row.label + "' (" + row.keyPrefix + ")");
        return new ApiKeyCreated(row.id, app.id, row.label, row.keyPrefix, ApiKeys.mask(row.keyPrefix), plaintext, row.createdAt);
    }

    /** Stores a key supplied from outside (seed keys from the environment); returns the existing row if already known. */
    @Transactional
    public ApplicationApiKey register(Long applicationId, String label, String plaintext) {
        String hash = ApiKeys.hash(plaintext);
        Optional<ApplicationApiKey> existing = keys.findByKeyHash(hash);
        if (existing.isPresent()) return existing.get();
        ApplicationApiKey row = new ApplicationApiKey();
        row.applicationId = applicationId;
        row.label = label;
        row.keyPrefix = ApiKeys.prefixOf(plaintext);
        row.keyHash = hash;
        row.createdAt = Times.now(clock);
        return keys.save(row);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(Long applicationId) {
        requireApplication(applicationId);
        return keys.findByApplicationIdOrderByIdDesc(applicationId).stream().map(ApiKeyService::toDto).toList();
    }

    @Transactional
    public void revoke(Long apiKeyId) {
        ApplicationApiKey key = keys.findById(apiKeyId).orElseThrow(() -> ApiException.notFound("API key not found"));
        if (key.revokedAt != null) return;
        key.revokedAt = Times.now(clock);
        audit.success(AuditAction.API_KEY_REVOKED, "API_KEY", String.valueOf(key.id), key.applicationId,
                "Revoked key '" + key.label + "' (" + key.keyPrefix + ")");
    }

    private ApplicationService requireApplication(Long id) {
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("Application not found"));
    }

    private static ApiKey toDto(ApplicationApiKey k) {
        Instant revoked = k.revokedAt;
        return new ApiKey(k.id, k.applicationId, k.label, k.keyPrefix, ApiKeys.mask(k.keyPrefix), k.createdAt,
                k.lastUsedAt, revoked, revoked == null);
    }
}

package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Only a SHA-256 hash and a short prefix of the key are stored; the plaintext is shown once at creation. */
@Entity
@Table(name = "application_api_key")
public class ApplicationApiKey {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long applicationId;
    @Column(nullable = false, length = 120)
    public String label;
    @Column(nullable = false, length = 16)
    public String keyPrefix;
    @Column(nullable = false, length = 64)
    public String keyHash;
    @Column(nullable = false)
    public Instant createdAt;
    public Instant lastUsedAt;
    public Instant revokedAt;

    public boolean isActive() {
        return revokedAt == null;
    }
}

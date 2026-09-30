package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Audit trail row. {@code details} never contains keys, values, tokens or credentials. */
@Entity
@Table(name = "audit_log")
public class AuditLogEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Instant occurredAt;
    @Column(nullable = false, length = 80)
    public String actor;
    @Column(nullable = false, length = 16)
    public String actorRole;
    @Column(nullable = false, length = 40)
    public String action;
    @Column(length = 32)
    public String targetType;
    @Column(length = 80)
    public String targetId;
    public Long applicationId;
    @Column(length = 1000)
    public String details;
    @Column(nullable = false, length = 16)
    public String outcome;
}

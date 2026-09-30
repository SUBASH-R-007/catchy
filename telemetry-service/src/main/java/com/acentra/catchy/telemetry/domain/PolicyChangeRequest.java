package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Engineer-requested eviction policy change: PENDING, then APPROVED and APPLIED, or REJECTED. */
@Entity
@Table(name = "policy_change_request")
public class PolicyChangeRequest {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";
    public static final String APPLIED = "APPLIED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long applicationId;
    @Column(nullable = false, length = 63)
    public String cacheRegion;
    @Column(nullable = false, length = 8)
    public String currentPolicy;
    @Column(nullable = false, length = 8)
    public String requestedPolicy;
    @Column(length = 500)
    public String reason;
    @Column(nullable = false, length = 16)
    public String status;
    @Column(nullable = false, length = 64)
    public String requestedBy;
    @Column(length = 64)
    public String decidedBy;
    @Column(length = 500)
    public String decisionNote;
    @Column(nullable = false)
    public Instant createdAt;
    public Instant decidedAt;
    public Instant appliedAt;
    public Long recommendationId;
}

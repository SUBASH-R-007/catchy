package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Latest deterministic Policy Arena result for one region (updated in place, id is stable). */
@Entity
@Table(name = "cache_policy_recommendation")
public class CachePolicyRecommendationEntity {
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
    public String recommendedPolicy;
    @Column(nullable = false, length = 8)
    public String action;
    @Column(nullable = false, length = 120)
    public String summary;
    public double lruShadowHitRate;
    public double lfuShadowHitRate;
    public double improvementPercent;
    public int confidence;
    @Column(nullable = false, length = 1000)
    public String reason;
    @Column(length = 2000)
    public String aiExplanation;
    public long sampleSize;
    public boolean minimumSampleMet;
    public boolean cooldownActive;
    public Instant cooldownEndsAt;
    public boolean approvalRequired;
    @Column(nullable = false)
    public Instant createdAt;
}

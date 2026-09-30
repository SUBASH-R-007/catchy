package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A monitored application (one deployment of a service in one environment). */
@Entity
@Table(name = "application_service")
public class ApplicationService {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false)
    public Long projectId;
    @Column(nullable = false, length = 63)
    public String name;
    @Column(nullable = false, length = 120)
    public String displayName;
    @Column(nullable = false, length = 31)
    public String environment;
    @Column(length = 500)
    public String description;
    @Column(nullable = false)
    public Instant createdAt;
    public Instant lastTelemetryAt;
    @Column(nullable = false)
    public int regionCount;
}

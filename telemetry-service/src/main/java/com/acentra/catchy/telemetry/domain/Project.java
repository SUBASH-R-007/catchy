package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persistence holder (field access); behaviour lives in the services. */
@Entity
@Table(name = "project")
public class Project {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
    @Column(nullable = false, length = 80)
    public String name;
    @Column(nullable = false, length = 80)
    public String nameLower;
    @Column(length = 500)
    public String description;
    @Column(nullable = false)
    public Instant createdAt;
}

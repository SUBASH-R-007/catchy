package com.acentra.catchy.telemetry.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    boolean existsByNameLower(String nameLower);

    Optional<Project> findByNameLower(String nameLower);
}

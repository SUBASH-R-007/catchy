package com.acentra.catchy.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApplicationRepository extends JpaRepository<ApplicationService, Long> {
    List<ApplicationService> findByProjectIdOrderById(Long projectId);

    List<ApplicationService> findAllByOrderById();

    boolean existsByNameAndEnvironment(String name, String environment);

    Optional<ApplicationService> findByNameAndEnvironment(String name, String environment);

    long countByProjectId(Long projectId);

    @Modifying
    @Query("update ApplicationService a set a.lastTelemetryAt = :at, a.regionCount = :regions where a.id = :id")
    int touchTelemetry(@Param("id") Long id, @Param("at") Instant at, @Param("regions") int regions);
}

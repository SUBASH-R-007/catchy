package com.acentra.catchy.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApplicationApiKey, Long> {
    Optional<ApplicationApiKey> findByKeyHash(String keyHash);

    List<ApplicationApiKey> findByApplicationIdOrderByIdDesc(Long applicationId);

    @Modifying
    @Query("update ApplicationApiKey k set k.lastUsedAt = :at where k.id = :id")
    int touch(@Param("id") Long id, @Param("at") Instant at);
}

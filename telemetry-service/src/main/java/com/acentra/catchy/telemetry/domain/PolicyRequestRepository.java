package com.acentra.catchy.telemetry.domain;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.Collection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PolicyRequestRepository extends JpaRepository<PolicyChangeRequest, Long> {
    List<PolicyChangeRequest> findByApplicationIdOrderByIdDesc(Long applicationId);

    List<PolicyChangeRequest> findByApplicationIdAndCacheRegionAndStatus(Long applicationId, String cacheRegion, String status);

    List<PolicyChangeRequest> findByApplicationIdAndStatusIn(Long applicationId, Collection<String> statuses);

    List<PolicyChangeRequest> findByStatusIn(Collection<String> statuses);

    boolean existsByApplicationIdAndCacheRegionAndStatus(Long applicationId, String cacheRegion, String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PolicyChangeRequest r where r.id = :id")
    Optional<PolicyChangeRequest> findByIdForUpdate(@Param("id") Long id);
}

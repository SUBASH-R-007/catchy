package com.acentra.catchy.telemetry.domain;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<CacheTelemetryEvent, Long>, JpaSpecificationExecutor<CacheTelemetryEvent> {
    boolean existsByApplicationIdAndCacheRegion(Long applicationId, String cacheRegion);

    long countByApplicationId(Long applicationId);

    /** Ids of the application's events, newest first (used to find the retention cut-off id). */
    @Query("select e.id from CacheTelemetryEvent e where e.applicationId = :applicationId order by e.id desc")
    List<Long> findIdsNewestFirst(@Param("applicationId") Long applicationId, Pageable pageable);

    @Modifying
    @Query("delete from CacheTelemetryEvent e where e.applicationId = :applicationId and e.id <= :maxId")
    int deleteUpTo(@Param("applicationId") Long applicationId, @Param("maxId") Long maxId);
}

package com.acentra.catchy.telemetry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SnapshotRepository extends JpaRepository<CacheMetricsSnapshot, Long> {

    List<CacheMetricsSnapshot> findByLatestTrue();

    List<CacheMetricsSnapshot> findByApplicationIdAndLatestTrue(Long applicationId);

    List<CacheMetricsSnapshot> findByApplicationIdAndCacheRegionAndLatestTrue(Long applicationId, String cacheRegion);

    Optional<CacheMetricsSnapshot> findFirstByApplicationIdAndCacheRegionAndInstanceIdAndLatestTrueOrderByCapturedAtDesc(
            Long applicationId, String cacheRegion, String instanceId);

    boolean existsByApplicationIdAndCacheRegion(Long applicationId, String cacheRegion);

    @Query("select count(distinct s.cacheRegion) from CacheMetricsSnapshot s where s.applicationId = :id and s.latest = true")
    long countRegions(@Param("id") Long applicationId);

    @Query("select new com.acentra.catchy.telemetry.domain.SnapshotPoint(s.applicationId, s.cacheRegion, s.instanceId, "
            + "s.capturedAt, s.hits, s.misses, s.puts, s.evictions, s.expirations) from CacheMetricsSnapshot s "
            + "where s.capturedAt >= :from and s.capturedAt <= :to order by s.capturedAt, s.id")
    List<SnapshotPoint> pointsGlobal(@Param("from") Instant from, @Param("to") Instant to);

    @Query("select new com.acentra.catchy.telemetry.domain.SnapshotPoint(s.applicationId, s.cacheRegion, s.instanceId, "
            + "s.capturedAt, s.hits, s.misses, s.puts, s.evictions, s.expirations) from CacheMetricsSnapshot s "
            + "where s.applicationId = :appId and s.capturedAt >= :from and s.capturedAt <= :to order by s.capturedAt, s.id")
    List<SnapshotPoint> pointsForApplication(@Param("appId") Long appId, @Param("from") Instant from, @Param("to") Instant to);

    @Query("select new com.acentra.catchy.telemetry.domain.SnapshotPoint(s.applicationId, s.cacheRegion, s.instanceId, "
            + "s.capturedAt, s.hits, s.misses, s.puts, s.evictions, s.expirations) from CacheMetricsSnapshot s "
            + "where s.applicationId = :appId and s.cacheRegion = :region and s.capturedAt >= :from and s.capturedAt <= :to "
            + "order by s.capturedAt, s.id")
    List<SnapshotPoint> pointsForRegion(@Param("appId") Long appId, @Param("region") String region,
                                        @Param("from") Instant from, @Param("to") Instant to);

    /** Retention: never deletes the row that currently defines an instance's latest state. */
    @Modifying
    @Query("delete from CacheMetricsSnapshot s where s.latest = false and s.capturedAt < :cutoff")
    int deleteHistoryOlderThan(@Param("cutoff") Instant cutoff);
}

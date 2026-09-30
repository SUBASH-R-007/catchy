package com.acentra.catchy.telemetry.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<CachePolicyRecommendationEntity, Long> {
    List<CachePolicyRecommendationEntity> findByApplicationIdOrderByCacheRegion(Long applicationId);

    Optional<CachePolicyRecommendationEntity> findByApplicationIdAndCacheRegion(Long applicationId, String cacheRegion);
}

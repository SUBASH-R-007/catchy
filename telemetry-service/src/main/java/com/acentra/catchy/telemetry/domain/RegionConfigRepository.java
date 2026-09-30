package com.acentra.catchy.telemetry.domain;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RegionConfigRepository extends JpaRepository<RegionConfigOverride, Long> {
    Optional<RegionConfigOverride> findByApplicationIdAndCacheRegion(Long applicationId, String cacheRegion);

    List<RegionConfigOverride> findByApplicationId(Long applicationId);
}

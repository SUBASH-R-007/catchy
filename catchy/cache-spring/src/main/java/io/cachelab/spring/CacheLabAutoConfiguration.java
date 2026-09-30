package io.cachelab.spring;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Auto-configures CacheLab as the Spring cache provider (SPEC 7). Active unless {@code
 * cachelab.enabled=false}; runs before Spring Boot's own cache auto-configuration so that
 * {@code @EnableCaching} uses CacheLab. The Micrometer binder is added only when Micrometer is on
 * the classpath.
 */
@AutoConfiguration(before = CacheAutoConfiguration.class)
@ConditionalOnClass(CacheManager.class)
@ConditionalOnProperty(
    prefix = "cachelab",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(CacheLabProperties.class)
public class CacheLabAutoConfiguration {

  /** Creates the auto-configuration. */
  public CacheLabAutoConfiguration() {}

  /**
   * The CacheLab cache manager, unless the application defines its own {@link CacheManager}.
   *
   * @param properties bound {@code cachelab.*} properties
   * @return the manager
   */
  @Bean
  @ConditionalOnMissingBean(CacheManager.class)
  public CacheLabCacheManager cacheManager(CacheLabProperties properties) {
    return new CacheLabCacheManager(properties);
  }

  /** Micrometer integration, present only when Micrometer is on the classpath. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(MeterRegistry.class)
  static class MetricsConfiguration {

    /**
     * The binder; Spring Boot binds every {@code MeterBinder} bean to its registries.
     *
     * @param manager the CacheLab cache manager
     * @return the binder
     */
    @Bean
    @ConditionalOnMissingBean
    CacheLabMetrics cacheLabMetrics(CacheLabCacheManager manager) {
      return new CacheLabMetrics(manager);
    }
  }
}

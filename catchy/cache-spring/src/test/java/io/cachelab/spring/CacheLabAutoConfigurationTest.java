package io.cachelab.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.cachelab.Cache;
import io.cachelab.PolicyType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CacheLabAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(CacheLabAutoConfiguration.class, CacheAutoConfiguration.class));

  @Configuration(proxyBeanMethods = false)
  @EnableCaching
  static class App {
    @Bean
    Formulary formulary() {
      return new Formulary();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  static class Formulary {
    private final AtomicInteger calls = new AtomicInteger();

    /** Read through a method: fields of the caching proxy itself are never initialised. */
    public int calls() {
      return calls.get();
    }

    @Cacheable("formulary")
    public String find(int id) {
      calls.incrementAndGet();
      return "drug-" + id;
    }

    @Cacheable("dynamic")
    public String other(int id) {
      calls.incrementAndGet();
      return "x" + id;
    }
  }

  @Test
  void configuresCachesFromProperties() {
    runner
        .withPropertyValues(
            "cachelab.caches.formulary.maximum-size=500",
            "cachelab.caches.formulary.policy=lfu",
            "cachelab.caches.formulary.default-ttl=10m",
            "cachelab.caches.formulary.concurrency-level=16")
        .run(
            ctx -> {
              CacheManager manager = ctx.getBean(CacheManager.class);
              assertThat(manager).isInstanceOf(CacheLabCacheManager.class);
              @SuppressWarnings("unchecked")
              Cache<Object, Object> formulary =
                  (Cache<Object, Object>) manager.getCache("formulary").getNativeCache();
              assertThat(formulary.policyType()).isEqualTo(PolicyType.LFU);
              formulary.put("k", "v");
              assertThat(formulary.ttlRemaining("k"))
                  .hasValueSatisfying(
                      ttl ->
                          assertThat(ttl)
                              .isGreaterThan(Duration.ofMinutes(9))
                              .isLessThanOrEqualTo(Duration.ofMinutes(10)));
            });
  }

  @Test
  void cacheableMethodsUseCacheLabAndAreMetered() {
    runner
        .withUserConfiguration(App.class)
        .withPropertyValues("cachelab.caches.formulary.policy=lfu")
        .run(
            ctx -> {
              Formulary formulary = ctx.getBean(Formulary.class);
              assertThat(formulary.find(1)).isEqualTo("drug-1");
              assertThat(formulary.find(1)).isEqualTo("drug-1");
              assertThat(formulary.calls()).isEqualTo(1);
              formulary.other(7); // a cache created on first use is metered too
              formulary.other(7);

              MeterRegistry registry = ctx.getBean(MeterRegistry.class);
              // Boot binds MeterBinder beans only with its metrics auto-configuration; bind here.
              ctx.getBean(CacheLabMetrics.class).bindTo(registry);
              assertThat(
                      registry
                          .get("cache.gets")
                          .tags("cache", "formulary", "result", "hit")
                          .functionCounter()
                          .count())
                  .isEqualTo(1);
              assertThat(
                      registry
                          .get("cache.gets")
                          .tags("cache", "formulary", "result", "miss")
                          .functionCounter()
                          .count())
                  .isEqualTo(1);
              assertThat(registry.get("cache.size").tags("cache", "formulary").gauge().value())
                  .isEqualTo(1);
              assertThat(registry.find("cache.gets").tags("cache", "dynamic").functionCounters())
                  .hasSize(2);
            });
  }

  @Test
  void canBeDisabled() {
    runner
        .withPropertyValues("cachelab.enabled=false")
        .run(ctx -> assertThat(ctx).doesNotHaveBean(CacheLabCacheManager.class));
  }
}

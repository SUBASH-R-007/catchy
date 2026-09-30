/** Code shown on the Integrations page (SPEC 7, 10.5 item 6). */

export const GRADLE_SNIPPET = `dependencies {
    implementation("io.cachelab:cache-spring:0.1.0")
}`;

export const MAVEN_SNIPPET = `<dependency>
    <groupId>io.cachelab</groupId>
    <artifactId>cache-spring</artifactId>
    <version>0.1.0</version>
</dependency>`;

export const BUILDER_SNIPPET = `Cache<String, Drug> formulary = CacheBuilder.<String, Drug>newBuilder()
        .name("formulary")
        .maximumSize(10_000)
        .evictionPolicy(PolicyType.LFU)
        .defaultTtl(Duration.ofMinutes(10))
        .build();

// Cache-aside with single-flight loading: concurrent misses call the loader once.
Drug drug = formulary.getOrLoad(id, repository::findById);

formulary.put(id, drug, Duration.ofMinutes(1));   // per-entry TTL
Optional<Drug> cached = formulary.get(id);         // empty on a miss or after expiry`;

export const CACHEABLE_SNIPPET = `@Service
public class DrugService {

    @Cacheable("formulary")
    public Drug find(String id) {
        return repository.findById(id);   // runs only on a cache miss
    }
}`;

export const PROPERTIES_SNIPPET = `cachelab.caches.formulary.maximum-size=10000
cachelab.caches.formulary.policy=lfu
cachelab.caches.formulary.default-ttl=10m
cachelab.caches.formulary.concurrency-level=16`;

export const PROMETHEUS_CONFIG_SNIPPET = `# build.gradle.kts
implementation("org.springframework.boot:spring-boot-starter-actuator")
runtimeOnly("io.micrometer:micrometer-registry-prometheus")

# application.properties
management.endpoints.web.exposure.include=health,prometheus

# then scrape
GET /actuator/prometheus`;

/** Illustrative scrape output: the meter names are real, the numbers are made up. */
export const PROMETHEUS_EXAMPLE_OUTPUT = `cache_gets_total{cache="formulary",result="hit"} 18432.0
cache_gets_total{cache="formulary",result="miss"} 1568.0
cache_evictions_total{cache="formulary"} 212.0
cache_expirations_total{cache="formulary"} 37.0
cache_size{cache="formulary"} 10000.0
cache_loads_total{cache="formulary",result="success"} 1568.0
cache_loads_total{cache="formulary",result="failure"} 0.0`;

export interface Reason {
  title: string;
  detail: string;
}

export const WHY_CACHELAB: readonly Reason[] = [
  {
    title: 'O(1) LRU and LFU',
    detail: 'Every get, put and eviction is constant time, including LFU (frequency buckets).',
  },
  {
    title: 'TTL independent of eviction',
    detail: 'Entries expire on time whichever policy evicts them; stale values are never returned.',
  },
  {
    title: 'Proven thread safety',
    detail: 'A stress harness checks five invariants under 64 threads; loads are single-flight.',
  },
  {
    title: 'Policy advisor',
    detail: 'Shadow caches compare policies on your real traffic and recommend a switch.',
  },
  {
    title: 'Spring Boot starter',
    detail: '@Cacheable works unchanged, configured from application.properties, with Micrometer.',
  },
  {
    title: 'Zero dependencies',
    detail: 'cache-core pulls in nothing at runtime: just the JDK.',
  },
];

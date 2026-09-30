package io.cachelab.examples.formulary;

import io.cachelab.Cache;
import io.cachelab.CacheStats;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.stereotype.Repository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** A drug formulary lookup service: a slow simulated database fronted by CacheLab. */
@SpringBootApplication
@EnableCaching
public class FormularyApplication {

  /** Starts the service. @param args command-line arguments */
  public static void main(String[] args) {
    SpringApplication.run(FormularyApplication.class, args);
  }

  /** One formulary entry. @param id drug id @param name drug name @param tier coverage tier */
  public record Drug(int id, String name, int tier) {}

  /** Simulated database: 50,000 drugs, 30 ms per lookup. */
  @Repository
  public static class DrugRepository {
    /** Loads one drug slowly. @param id 0..49,999 @return the drug */
    public Drug load(int id) {
      if (id < 0 || id >= 50_000) {
        throw new IllegalArgumentException("unknown drug " + id);
      }
      try {
        Thread.sleep(30);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return new Drug(id, "drug-" + id, 1 + id % 4);
    }
  }

  /** The cached service. */
  @Service
  public static class DrugService {
    private final DrugRepository repository;

    /**
     * @param repository the slow database
     */
    public DrugService(DrugRepository repository) {
      this.repository = repository;
    }

    /** Finds a drug, cached in "formulary". @param id drug id @return the drug */
    @Cacheable("formulary")
    public Drug find(int id) {
      return repository.load(id);
    }
  }

  /** REST endpoints. */
  @RestController
  public static class DrugController {
    private final DrugService service;
    private final CacheManager caches;
    private final AtomicLongArray lastNanos = new AtomicLongArray(1_000);
    private final AtomicInteger calls = new AtomicInteger();

    /**
     * @param service drugs @param caches the CacheLab cache manager
     */
    public DrugController(DrugService service, CacheManager caches) {
      this.service = service;
      this.caches = caches;
    }

    /**
     * @param id drug id @return the drug
     */
    @GetMapping("/drugs/{id}")
    public Drug drug(@PathVariable int id) {
      long start = System.nanoTime();
      Drug drug = service.find(id);
      lastNanos.set(Math.floorMod(calls.getAndIncrement(), 1_000), System.nanoTime() - start);
      return drug;
    }

    /** Cache stats plus the mean response time of the last 1,000 calls. */
    public record Stats(long hits, long misses, double hitRate, long evictions, double avgMs) {}

    /**
     * @return the formulary cache's stats and recent mean response time
     */
    @GetMapping("/stats")
    public Stats stats() {
      CacheStats s = ((Cache<?, ?>) caches.getCache("formulary").getNativeCache()).stats();
      int n = Math.min(calls.get(), 1_000);
      long total = 0;
      for (int i = 0; i < n; i++) {
        total += lastNanos.get(i);
      }
      double avgMs = n == 0 ? 0.0 : total / 1e6 / n;
      return new Stats(s.hitCount(), s.missCount(), s.hitRate(), s.evictionCount(), avgMs);
    }
  }
}

package io.cachelab.server.api;

import io.cachelab.Cache;
import io.cachelab.PolicySnapshot;
import io.cachelab.server.api.CacheDtos.CacheInfo;
import io.cachelab.server.api.CacheDtos.EntryDto;
import io.cachelab.server.api.CacheDtos.GetResult;
import io.cachelab.server.api.CacheDtos.PolicyRequest;
import io.cachelab.server.api.CacheDtos.PutRequest;
import io.cachelab.server.api.CacheDtos.RemoveResult;
import io.cachelab.server.cache.CacheConfig;
import io.cachelab.server.cache.CacheRegistry;
import io.cachelab.server.cache.ManagedCache;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The cache API (SPEC 8.2): cache CRUD, entries, snapshots, policy switch and stats reset. */
@RestController
@RequestMapping("/api/caches")
public class CacheController {

  private final CacheRegistry registry;

  /**
   * Creates the controller.
   *
   * @param registry the managed caches
   */
  public CacheController(CacheRegistry registry) {
    this.registry = registry;
  }

  /**
   * Lists every cache with live stats.
   *
   * @return the caches, sorted by group then name
   */
  @GetMapping
  public List<CacheInfo> list() {
    return registry.list().stream().map(CacheInfo::of).toList();
  }

  /**
   * Creates a cache.
   *
   * @param config the configuration; omitted optional fields get defaults
   * @return 201 with the effective configuration
   */
  @PostMapping
  public ResponseEntity<CacheConfig> create(@Valid @RequestBody CacheConfig config) {
    ManagedCache created = registry.create(config);
    return ResponseEntity.created(URI.create("/api/caches/" + created.name()))
        .body(created.config());
  }

  /**
   * Deletes a cache.
   *
   * @param name the cache
   * @return 204
   */
  @DeleteMapping("/{name}")
  public ResponseEntity<Void> delete(@PathVariable String name) {
    registry.delete(name);
    return ResponseEntity.noContent().build();
  }

  /**
   * Lists live entries in policy order without counting as access.
   *
   * @param name the cache
   * @param limit maximum entries, 0–1000
   * @return the entries
   */
  @GetMapping("/{name}/entries")
  public List<EntryDto> entries(
      @PathVariable String name, @RequestParam(defaultValue = "50") @Min(0) @Max(1000) int limit) {
    return registry.get(name).cache().entries(limit).stream().map(EntryDto::of).toList();
  }

  /**
   * Looks one key up; this is a normal get and counts as a hit or miss.
   *
   * @param name the cache
   * @param key the key
   * @return hit flag, value and remaining TTL
   */
  @GetMapping("/{name}/entries/{key}")
  public GetResult get(@PathVariable String name, @PathVariable @Size(max = 200) String key) {
    Cache<String, String> cache = registry.get(name).cache();
    Optional<String> value = cache.get(key);
    Long ttl = value.isPresent() ? CacheDtos.millis(cache.ttlRemaining(key)) : null;
    return new GetResult(value.isPresent(), value.orElse(null), ttl);
  }

  /**
   * Stores a value, with an optional per-entry TTL.
   *
   * @param name the cache
   * @param key the key
   * @param body the value and TTL
   * @return 204
   */
  @PutMapping("/{name}/entries/{key}")
  public ResponseEntity<Void> put(
      @PathVariable String name,
      @PathVariable @Size(max = 200) String key,
      @Valid @RequestBody PutRequest body) {
    Cache<String, String> cache = registry.get(name).cache();
    if (body.ttlMs() == null) {
      cache.put(key, body.value());
    } else {
      cache.put(key, body.value(), Duration.ofMillis(body.ttlMs()));
    }
    return ResponseEntity.noContent().build();
  }

  /**
   * Removes one key.
   *
   * @param name the cache
   * @param key the key
   * @return whether a live entry was removed
   */
  @DeleteMapping("/{name}/entries/{key}")
  public RemoveResult remove(@PathVariable String name, @PathVariable String key) {
    return new RemoveResult(registry.get(name).cache().remove(key));
  }

  /**
   * Returns the policy's internal order.
   *
   * @param name the cache
   * @param limit maximum entries, 0–1000
   * @return the snapshot
   */
  @GetMapping("/{name}/snapshot")
  public PolicySnapshot<String> snapshot(
      @PathVariable String name, @RequestParam(defaultValue = "20") @Min(0) @Max(1000) int limit) {
    return registry.get(name).cache().policySnapshot(limit);
  }

  /**
   * Switches the eviction policy at runtime; switching a group's primary resets its advisor.
   *
   * @param name the cache
   * @param body the new policy
   * @return the updated configuration
   */
  @PostMapping("/{name}/policy")
  public CacheConfig switchPolicy(
      @PathVariable String name, @Valid @RequestBody PolicyRequest body) {
    return registry.switchPolicy(name, body.policy());
  }

  /**
   * Makes the reported stats start again from zero.
   *
   * @param name the cache
   * @return 204
   */
  @PostMapping("/{name}/reset-stats")
  public ResponseEntity<Void> resetStats(@PathVariable String name) {
    registry.get(name).resetStats();
    return ResponseEntity.noContent().build();
  }
}

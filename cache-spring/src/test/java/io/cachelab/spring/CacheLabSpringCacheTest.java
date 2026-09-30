package io.cachelab.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.cachelab.CacheBuilder;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache.ValueRetrievalException;

class CacheLabSpringCacheTest {

  private final CacheLabSpringCache cache =
      new CacheLabSpringCache(
          CacheBuilder.<Object, Object>newBuilder().name("spring-test").maximumSize(10).build());

  @AfterEach
  void close() {
    cache.getNativeCache().close();
  }

  @Test
  void storesAndReadsValues() {
    assertThat(cache.getName()).isEqualTo("spring-test");
    assertThat(cache.get("missing")).isNull();
    cache.put("k", "v");
    assertThat(cache.get("k").get()).isEqualTo("v");
    assertThat(cache.get("k", String.class)).isEqualTo("v");
    assertThatThrownBy(() -> cache.get("k", Integer.class))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void aCachedNullIsAHitThatReturnsNull() {
    cache.put("nothing", null);
    assertThat(cache.get("nothing")).isNotNull();
    assertThat(cache.get("nothing").get()).isNull();
    assertThat(cache.get("nothing", String.class)).isNull();
    assertThat(cache.getNativeCache().get("nothing")).isPresent(); // stored as the marker
  }

  @Test
  void valueLoaderRunsOnceAndItsNullIsCached() throws Exception {
    AtomicInteger calls = new AtomicInteger();
    assertThat(
            cache.<String>get(
                "k",
                () -> {
                  calls.incrementAndGet();
                  return null;
                }))
        .isNull();
    assertThat(cache.<String>get("k", () -> "never")).isNull();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void loaderFailuresBecomeValueRetrievalExceptions() {
    assertThatThrownBy(
            () ->
                cache.get(
                    "k",
                    () -> {
                      throw new IOException("database down");
                    }))
        .isInstanceOf(ValueRetrievalException.class)
        .hasRootCauseInstanceOf(IOException.class)
        .hasRootCauseMessage("database down");
    assertThat(cache.get("k")).isNull();
  }

  @Test
  void evictAndClear() {
    cache.put("a", 1);
    cache.put("b", 2);
    assertThat(cache.evictIfPresent("a")).isTrue();
    assertThat(cache.evictIfPresent("a")).isFalse();
    cache.evict("b");
    cache.put("c", 3);
    cache.clear();
    assertThat(cache.getNativeCache().size()).isZero();
  }
}

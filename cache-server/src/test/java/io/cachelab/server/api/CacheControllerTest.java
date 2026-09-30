package io.cachelab.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cachelab.RemovalCause;
import io.cachelab.server.cache.EventRing;
import io.cachelab.server.metrics.RemovalEvent;
import io.cachelab.testing.FakeTicker;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class CacheControllerTest {

  @TestConfiguration
  static class FakeTime {
    @Bean
    @Primary
    FakeTicker fakeTicker() {
      return new FakeTicker();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired FakeTicker ticker;
  @Autowired EventRing events;

  private ResultActions create(String json) throws Exception {
    return mvc.perform(post("/api/caches").contentType(MediaType.APPLICATION_JSON).content(json));
  }

  private ResultActions putEntry(String cache, String key, String json) throws Exception {
    return mvc.perform(
        put("/api/caches/{c}/entries/{k}", cache, key)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  @Test
  void bootsWithTheDemoGroup() throws Exception {
    mvc.perform(get("/api/caches"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.name=='lru-A')].policy").value(hasItem("LRU")))
        .andExpect(jsonPath("$[?(@.name=='lfu-A')].policy").value(hasItem("LFU")))
        .andExpect(jsonPath("$[?(@.name=='lfu-A')].capacity").value(hasItem(1000)))
        .andExpect(jsonPath("$[?(@.name=='lfu-A')].group").value(hasItem("demo")));
  }

  @Test
  void createsACacheWithDefaultsAndRejectsDuplicates() throws Exception {
    create("{\"name\":\"p-defaults\",\"capacity\":5}")
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/caches/p-defaults"))
        .andExpect(jsonPath("$.policy").value("LRU"))
        .andExpect(jsonPath("$.concurrencyLevel").value(1))
        .andExpect(jsonPath("$.group").value("playground"))
        .andExpect(jsonPath("$.defaultTtlMs").value(nullValue()));
    create("{\"name\":\"p-defaults\",\"capacity\":5}")
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("A cache named 'p-defaults' already exists"));
  }

  @Test
  void rejectsInvalidConfigurationsWithProblemDetails() throws Exception {
    create("{\"name\":\"bad name!\",\"capacity\":5}")
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", containsString("application/problem+json")));
    create("{\"name\":\"zero\",\"capacity\":0}").andExpect(status().isBadRequest());
    create("{\"name\":\"odd\",\"capacity\":8,\"concurrencyLevel\":3}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("power of two")));
    create("{\"name\":\"ttl\",\"capacity\":8,\"defaultTtlMs\":0}")
        .andExpect(status().isBadRequest());
  }

  @Test
  void putGetAndRemoveReportHitsAndMisses() throws Exception {
    create("{\"name\":\"p-ops\",\"capacity\":5}").andExpect(status().isCreated());
    mvc.perform(get("/api/caches/p-ops/entries/drug:1"))
        .andExpect(jsonPath("$.hit").value(false))
        .andExpect(jsonPath("$.value").value(nullValue()));
    putEntry("p-ops", "drug:1", "{\"value\":\"aspirin\"}").andExpect(status().isNoContent());
    mvc.perform(get("/api/caches/p-ops/entries/drug:1"))
        .andExpect(jsonPath("$.hit").value(true))
        .andExpect(jsonPath("$.value").value("aspirin"))
        .andExpect(jsonPath("$.ttlRemainingMs").value(nullValue()));
    mvc.perform(delete("/api/caches/p-ops/entries/drug:1"))
        .andExpect(jsonPath("$.removed").value(true));
    mvc.perform(delete("/api/caches/p-ops/entries/drug:1"))
        .andExpect(jsonPath("$.removed").value(false));
    mvc.perform(get("/api/caches"))
        .andExpect(jsonPath("$[?(@.name=='p-ops')].hits").value(hasItem(1)))
        .andExpect(jsonPath("$[?(@.name=='p-ops')].misses").value(hasItem(1)))
        .andExpect(jsonPath("$[?(@.name=='p-ops')].hitRate").value(hasItem(0.5)));
  }

  @Test
  void anEntryWithTtlCountsDownAndThenMisses() throws Exception {
    create("{\"name\":\"p-ttl\",\"capacity\":5}").andExpect(status().isCreated());
    putEntry("p-ttl", "k", "{\"value\":\"v\",\"ttlMs\":3000}").andExpect(status().isNoContent());
    ticker.advance(Duration.ofMillis(1_000));
    mvc.perform(get("/api/caches/p-ttl/entries"))
        .andExpect(jsonPath("$[0].key").value("k"))
        .andExpect(jsonPath("$[0].ttlRemainingMs").value(2000));
    ticker.advance(Duration.ofMillis(2_000));
    mvc.perform(get("/api/caches/p-ttl/entries")).andExpect(jsonPath("$.length()").value(0));
    mvc.perform(get("/api/caches/p-ttl/entries/k")).andExpect(jsonPath("$.hit").value(false));
    assertThat(events.latest(200))
        .anySatisfy(e -> assertEvent(e, "p-ttl", "k", RemovalCause.EXPIRED));
  }

  @Test
  void evictionsReachTheEventRing() throws Exception {
    create("{\"name\":\"p-evict\",\"capacity\":1}").andExpect(status().isCreated());
    putEntry("p-evict", "a", "{\"value\":\"1\"}");
    putEntry("p-evict", "b", "{\"value\":\"2\"}");
    assertThat(events.latest(200))
        .anySatisfy(e -> assertEvent(e, "p-evict", "a", RemovalCause.EVICTED));
  }

  @Test
  void snapshotAndPolicySwitch() throws Exception {
    create("{\"name\":\"p-switch\",\"capacity\":5,\"policy\":\"LFU\"}")
        .andExpect(status().isCreated());
    putEntry("p-switch", "hot", "{\"value\":\"1\"}");
    putEntry("p-switch", "cold", "{\"value\":\"2\"}");
    mvc.perform(get("/api/caches/p-switch/entries/hot"));
    mvc.perform(get("/api/caches/p-switch/snapshot?limit=5"))
        .andExpect(jsonPath("$.type").value("LFU"))
        .andExpect(jsonPath("$.entries[0].key").value("hot"))
        .andExpect(jsonPath("$.entries[0].frequency").value(2));
    mvc.perform(
            post("/api/caches/p-switch/policy")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"policy\":\"LRU\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.policy").value("LRU"));
    mvc.perform(get("/api/caches/p-switch/snapshot"))
        .andExpect(jsonPath("$.type").value("LRU"))
        .andExpect(jsonPath("$.entries.length()").value(2));
  }

  @Test
  void resetStatsStartsCountingFromZero() throws Exception {
    create("{\"name\":\"p-reset\",\"capacity\":5}").andExpect(status().isCreated());
    mvc.perform(get("/api/caches/p-reset/entries/x"));
    mvc.perform(post("/api/caches/p-reset/reset-stats")).andExpect(status().isNoContent());
    mvc.perform(get("/api/caches"))
        .andExpect(jsonPath("$[?(@.name=='p-reset')].misses").value(hasItem(0)))
        .andExpect(jsonPath("$[?(@.name=='p-reset')].hitRate").value(hasItem(0.0)));
  }

  @Test
  void deletingACacheMakesItUnknown() throws Exception {
    create("{\"name\":\"p-delete\",\"capacity\":5}").andExpect(status().isCreated());
    mvc.perform(delete("/api/caches/p-delete")).andExpect(status().isNoContent());
    mvc.perform(get("/api/caches/p-delete/entries"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.detail").value("No cache named 'p-delete'"));
    mvc.perform(delete("/api/caches/p-delete")).andExpect(status().isNotFound());
  }

  @Test
  void validatesEntryRequests() throws Exception {
    create("{\"name\":\"p-valid\",\"capacity\":5}").andExpect(status().isCreated());
    putEntry("p-valid", "k", "{\"ttlMs\":5}").andExpect(status().isBadRequest());
    putEntry("p-valid", "k", "{\"value\":\"v\",\"ttlMs\":0}").andExpect(status().isBadRequest());
    mvc.perform(get("/api/caches/p-valid/entries?limit=-1")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/caches/p-valid/snapshot?limit=5000")).andExpect(status().isBadRequest());
  }

  /** Event timestamps come from the wall clock; compare everything else. */
  private static void assertEvent(RemovalEvent e, String cache, String key, RemovalCause cause) {
    assertThat(e.cache()).isEqualTo(cache);
    assertThat(e.key()).isEqualTo(key);
    assertThat(e.cause()).isEqualTo(cause);
  }
}

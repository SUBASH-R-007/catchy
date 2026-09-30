package io.cachelab.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.cachelab.diagnostics.StressConfig;
import io.cachelab.server.stress.StressBusyException;
import io.cachelab.server.stress.StressService;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class StressControllerTest {

  @Autowired MockMvc mvc;
  @Autowired StressService service;

  @Test
  void aStressRunReportsFivePassingInvariants() throws Exception {
    mvc.perform(
            post("/api/stress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"impl\":\"SEGMENTED\",\"threads\":4,\"durationMs\":300}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.impl").value("SEGMENTED"))
        .andExpect(jsonPath("$.threads").value(4))
        .andExpect(jsonPath("$.invariants", hasSize(5)))
        .andExpect(jsonPath("$.invariants[*].passed", everyItem(org.hamcrest.Matchers.is(true))))
        .andExpect(jsonPath("$.invariants[0].name").value("Size bound"))
        .andExpect(jsonPath("$.deadlockFree").value(true))
        .andExpect(jsonPath("$.exceptions", hasSize(0)));
  }

  @Test
  void theStampedeRunsTheLoaderOnce() throws Exception {
    mvc.perform(
            post("/api/stress/stampede")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"threads\":50,\"loaderDelayMs\":100}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.threads").value(50))
        .andExpect(jsonPath("$.loaderCalls").value(1))
        .andExpect(jsonPath("$.allSameValue").value(true));
  }

  @Test
  void rejectsOutOfRangeRequests() throws Exception {
    mvc.perform(
            post("/api/stress").contentType(MediaType.APPLICATION_JSON).content("{\"threads\":65}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/stress")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"impl\":\"SEGMENTED\",\"capacity\":8,\"durationMs\":100}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("16")));
  }

  @Test
  void onlyOneJobRunsAtATime() throws Exception {
    StressConfig long1 =
        new StressConfig(
            StressConfig.Impl.SINGLE_LOCK,
            2,
            Duration.ofMillis(1_500),
            100,
            0.5,
            64,
            io.cachelab.PolicyType.LRU,
            1);
    CompletableFuture<?> first = CompletableFuture.runAsync(() -> service.stress(long1));
    long waitUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!service.isBusy() && System.nanoTime() < waitUntil) {
      Thread.onSpinWait();
    }
    assertThat(service.isBusy()).isTrue();
    assertThatThrownBy(() -> service.stampede(2, Duration.ZERO))
        .isInstanceOf(StressBusyException.class);
    mvc.perform(post("/api/stress/stampede")).andExpect(status().isConflict());
    first.get(10, TimeUnit.SECONDS);
    assertThat(service.isBusy()).isFalse();
  }
}

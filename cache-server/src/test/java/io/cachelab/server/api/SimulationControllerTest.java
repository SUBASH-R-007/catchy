package io.cachelab.server.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cachelab.server.metrics.SimulationStatus;
import io.cachelab.server.simulation.SimulationService;
import io.cachelab.server.workload.KeyStreams;
import io.cachelab.server.workload.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
class SimulationControllerTest {

  @Autowired MockMvc mvc;
  @Autowired SimulationService simulations;
  @Autowired ObjectMapper json;

  @AfterEach
  void stop() {
    simulations.stopAll();
  }

  private ResultActions start(String body) throws Exception {
    return mvc.perform(
        post("/api/simulations").contentType(MediaType.APPLICATION_JSON).content(body));
  }

  @Test
  void startsWithDefaultsAndStops() throws Exception {
    String body =
        start("{\"group\":\"demo\",\"pattern\":\"ZIPF\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(matchesPattern("s-\\d+")))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = json.readTree(body).get("id").asText();

    SimulationStatus status = simulations.status();
    assertThat(status.id()).isEqualTo(id);
    assertThat(status.running()).isTrue();
    assertThat(status.phaseCaption()).isEqualTo(KeyStreams.describe(Pattern.ZIPF));
    assertThat(status.phaseCount()).isEqualTo(1);

    mvc.perform(delete("/api/simulations/{id}", id)).andExpect(status().isNoContent());
    assertThat(simulations.status().running()).isFalse();
    assertThat(simulations.summary(id).orElseThrow().plan().opsPerSec()).isEqualTo(5_000);
    assertThat(simulations.summary(id).orElseThrow().plan().readRatio()).isEqualTo(0.9);
    assertThat(simulations.summary(id).orElseThrow().plan().seed()).isEqualTo(42);
    assertThat(simulations.summary(id).orElseThrow().plan().phases().get(0).durationSec())
        .isEqualTo(60);
    mvc.perform(delete("/api/simulations/{id}", id)).andExpect(status().isNoContent());
  }

  @Test
  void unknownGroupIs404() throws Exception {
    start("{\"group\":\"nope\",\"pattern\":\"ZIPF\"}")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Group not found"))
        .andExpect(jsonPath("$.detail").value("No group named 'nope'"));
  }

  @Test
  void unknownSimulationIdIs404() throws Exception {
    mvc.perform(delete("/api/simulations/{id}", "s-987654"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.title").value("Simulation not found"));
  }

  @Test
  void invalidRequestsAre400() throws Exception {
    start("{\"pattern\":\"ZIPF\"}").andExpect(status().isBadRequest());
    start("{\"group\":\"demo\"}").andExpect(status().isBadRequest());
    start("{\"group\":\"demo\",\"pattern\":\"NOPE\"}").andExpect(status().isBadRequest());
    start("{\"group\":\"demo\",\"pattern\":\"ZIPF\",\"readRatio\":1.5}")
        .andExpect(status().isBadRequest());
    start("{\"group\":\"demo\",\"pattern\":\"ZIPF\",\"opsPerSec\":-1}")
        .andExpect(status().isBadRequest());
    start("{\"group\":\"demo\",\"pattern\":\"ZIPF\",\"durationSec\":0}")
        .andExpect(status().isBadRequest());
    start("{\"group\":\"demo\",\"pattern\":\"LOOP\",\"params\":{\"bogus\":1}}")
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.detail").value(containsString("Unknown parameter 'bogus'")));
    assertThat(simulations.status() == null || !simulations.status().running()).isTrue();
  }
}

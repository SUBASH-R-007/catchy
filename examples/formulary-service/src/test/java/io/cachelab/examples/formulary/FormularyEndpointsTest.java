package io.cachelab.examples.formulary;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class FormularyEndpointsTest {

  @Autowired MockMvc mvc;

  @Test
  void cacheableLookupsAndStats() throws Exception {
    mvc.perform(get("/drugs/42"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("drug-42"));
    mvc.perform(get("/drugs/42")).andExpect(status().isOk());
    mvc.perform(get("/stats"))
        .andExpect(jsonPath("$.hits").value(1))
        .andExpect(jsonPath("$.misses").value(1));
  }

  @Test
  void prometheusExposesTheCacheMeters() throws Exception {
    mvc.perform(get("/drugs/7"));
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("cache_gets_total{cache=\"formulary\"")))
        .andExpect(content().string(containsString("cache_size{cache=\"formulary\"")))
        .andExpect(content().string(containsString("cache_evictions_total{cache=\"formulary\"")));
  }
}

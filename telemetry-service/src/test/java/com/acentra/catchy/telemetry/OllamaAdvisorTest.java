package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/**
 * The optional Ollama advisor against a local stub server: advisory text only, aggregate numbers only, never on
 * ingestion or plain reads, and any failure leaves {@code aiExplanation} null without touching the decision.
 */
@TestPropertySource(properties = {
        "catchy.ollama.enabled=true",
        "catchy.ollama.timeout=PT2S",
        "spring.datasource.url=jdbc:h2:mem:catchy-ollama;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class OllamaAdvisorTest extends AbstractApiTest {

    private static final HttpServer SERVER = start();
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static final List<String> BODIES = new CopyOnWriteArrayList<>();
    private static volatile int statusCode = 200;

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/generate", exchange -> {
                CALLS.incrementAndGet();
                BODIES.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] response = "{\"response\":\"LFU fits this stable access pattern better.\",\"done\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(statusCode, statusCode == 200 ? response.length : -1);
                if (statusCode == 200) exchange.getResponseBody().write(response);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void ollamaUrl(DynamicPropertyRegistry registry) {
        registry.add("catchy.ollama.url", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        SERVER.stop(0);
    }

    @BeforeEach
    void reset() {
        CALLS.set(0);
        BODIES.clear();
        statusCode = 200;
    }

    private void report(TestApp app, String region) throws Exception {
        RegionSnapshot snap = TestData.snap().region(region).policy(EvictionPolicy.LRU).counters(700, 300, 300, 0, 0)
                .shadow(1000, 69.4, 80.9, 78.5).build();
        ingest(app.apiKey(), TestData.batch(null, null, "pod-a", List.of(), List.of(snap))).andExpect(status().isAccepted());
    }

    @Test
    void explanationIsOnlyRequestedByEvaluateAndOnlyFillsAiExplanation() throws Exception {
        TestApp app = createApp("claims-service");
        report(app, "claim-rules");
        assertThat(CALLS.get()).as("ingestion never calls Ollama").isZero();

        JsonNode plain = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations").andExpect(status().isOk())).get(0);
        assertThat(plain.get("aiExplanation").isNull()).isTrue();
        assertThat(CALLS.get()).as("plain reads never call Ollama").isZero();

        JsonNode evaluated = json(postAs("engineer", "/api/v1/applications/" + app.appId() + "/recommendations/evaluate", null)
                .andExpect(status().isOk())).get(0);
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(evaluated.get("aiExplanation").asText()).isEqualTo("LFU fits this stable access pattern better.");
        assertThat(evaluated.get("action").asText()).isEqualTo("SWITCH");
        assertThat(evaluated.get("recommendedPolicy").asText()).isEqualTo("LFU");
        assertThat(evaluated.get("id").asLong()).isEqualTo(plain.get("id").asLong());

        // the prompt carries aggregate numbers and policy names only
        String sent = BODIES.get(0);
        assertThat(sent).contains("LRU 69.4%").contains("LFU 80.9%").contains("1000").contains("78.5");
        assertThat(sent).doesNotContain("claim-rules").doesNotContain("claims-service").doesNotContain("sha256").doesNotContain("acc_");

        // the stored explanation is served on later reads without another call
        JsonNode later = json(getAs("viewer", "/api/v1/applications/" + app.appId() + "/recommendations")).get(0);
        assertThat(later.get("aiExplanation").asText()).isEqualTo("LFU fits this stable access pattern better.");
        assertThat(CALLS.get()).isEqualTo(1);
    }

    @Test
    void failuresLeaveTheExplanationNullAndTheDecisionUntouched() throws Exception {
        TestApp app = createApp("claims-service");
        report(app, "member-lookup");
        statusCode = 500;
        JsonNode r = json(postAs("engineer", "/api/v1/applications/" + app.appId() + "/recommendations/evaluate", null)
                .andExpect(status().isOk())).get(0);
        assertThat(CALLS.get()).isEqualTo(1);
        assertThat(r.get("aiExplanation").isNull()).isTrue();
        assertThat(r.get("action").asText()).isEqualTo("SWITCH");
        assertThat(r.get("summary").asText()).isEqualTo("Switch to LFU");
    }
}

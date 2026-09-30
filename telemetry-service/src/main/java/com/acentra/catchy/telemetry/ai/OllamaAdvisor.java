package com.acentra.catchy.telemetry.ai;

import com.acentra.cache.EvictionPolicy;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Optional, advisory-only natural-language explanation of a Policy Arena decision from a local Ollama instance.
 * <ul>
 *   <li>Disabled by default ({@code catchy.ollama.enabled=false}).</li>
 *   <li>Called only from the recommendation evaluate endpoint and its background job, never from ingestion.</li>
 *   <li>The prompt contains aggregate numbers only (policy names, simulated hit rates, sample size, concentration):
 *       no keys, fingerprints, values, application or region names.</li>
 *   <li>The text only fills {@code aiExplanation}; it can never change the deterministic action.</li>
 *   <li>Short timeout; any failure returns empty.</li>
 * </ul>
 */
@Component
public class OllamaAdvisor {
    private static final Logger log = LoggerFactory.getLogger(OllamaAdvisor.class);
    private static final int MAX_LENGTH = 600;

    /** Aggregate inputs of the explanation prompt. */
    public record Context(EvictionPolicy currentPolicy, EvictionPolicy recommendedPolicy, String action,
                          double lruHitRate, double lfuHitRate, long sampleSize, double topKeyConcentrationPercent) {}

    private final CatchyProperties.Ollama cfg;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public OllamaAdvisor(CatchyProperties props, ObjectMapper mapper) {
        this.cfg = props.ollama();
        this.mapper = mapper;
        this.http = HttpClient.newBuilder().connectTimeout(cfg.timeout()).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public Optional<String> explain(Context ctx) {
        if (!cfg.enabled()) return Optional.empty();
        try {
            String base = cfg.url().endsWith("/") ? cfg.url().substring(0, cfg.url().length() - 1) : cfg.url();
            byte[] body = mapper.writeValueAsBytes(Map.of(
                    "model", cfg.model(),
                    "prompt", prompt(ctx),
                    "stream", false,
                    "options", Map.of("temperature", 0.2, "num_predict", 160)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/generate"))
                    .timeout(cfg.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) return Optional.empty();
            JsonNode text = mapper.readTree(response.body()).get("response");
            if (text == null || !text.isTextual()) return Optional.empty();
            String cleaned = text.asText().replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").trim();
            if (cleaned.isEmpty()) return Optional.empty();
            return Optional.of(cleaned.length() > MAX_LENGTH ? cleaned.substring(0, MAX_LENGTH - 3) + "..." : cleaned);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            log.debug("Ollama advisor unavailable: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    static String prompt(Context c) {
        return String.format(Locale.ROOT,
                "You are a cache tuning assistant. In at most two short sentences, explain this decision to an engineer. "
                        + "Do not contradict the decision and do not invent numbers.%n"
                        + "Current eviction policy: %s.%n"
                        + "Simulated hit rate over the recent window: LRU %.1f%%, LFU %.1f%%.%n"
                        + "Requests in the window: %d.%n"
                        + "The top 20%% of keys receive %.1f%% of requests.%n"
                        + "Deterministic decision: %s (recommended policy %s).",
                c.currentPolicy(), c.lruHitRate(), c.lfuHitRate(), c.sampleSize(), c.topKeyConcentrationPercent(),
                c.action(), c.recommendedPolicy());
    }
}

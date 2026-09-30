package io.cachelab.server.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cachelab.PolicyType;
import io.cachelab.RemovalCause;
import io.cachelab.server.workload.Pattern;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Serializes metrics payloads with the application's {@link ObjectMapper} and validates them
 * against {@code docs/api/metrics.schema.json}.
 */
@JsonTest
class MetricsSchemaConformanceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.ofEpochMilli(1_790_000_000_000L), ZoneOffset.UTC);
  private static final CostModel COST = new CostModel(12.5, 0.05, "$");

  private static MiniJsonSchemaValidator validator;

  @Autowired private ObjectMapper objectMapper;

  @BeforeAll
  static void loadSchema() throws IOException {
    validator = MiniJsonSchemaValidator.forMetricsSchema();
  }

  @Test
  void everyFakeTickMatchesTheSchema() throws IOException {
    FakeMetricsSource source = new FakeMetricsSource(42, CLOCK, COST);
    Set<Integer> phases = new HashSet<>();
    for (int i = 0; i < 120; i++) {
      MetricsSnapshot tick = source.next();
      phases.add(tick.simulation().phaseIndex());
      JsonNode json = toJson(tick);
      assertThat(validator.validate(json)).as("tick %d: %s", i, json).isEmpty();
    }
    assertThat(phases).containsExactlyInAnyOrder(0, 1, 2);
  }

  @Test
  void nullsAreWrittenNotOmitted() throws IOException {
    JsonNode json = toJson(new FakeMetricsSource(42, CLOCK, COST).next());

    assertThat(json.get("simulation").has("act")).isTrue();
    assertThat(json.get("simulation").get("act").isNull()).isTrue();
    assertThat(json.get("groups").get(0).get("optimalHitRate").isNull()).isTrue();
    assertThat(json.get("groups").get(0).get("advisor").isNull()).isTrue();
    assertThat(json.get("caches").get(0).has("getP50Micros")).isTrue();
  }

  @Test
  void emptyPayloadMatchesTheSchema() throws IOException {
    JsonNode json = toJson(new MetricsSnapshot(1L, List.of(), List.of(), null, List.of()));

    assertThat(validator.validate(json)).isEmpty();
    assertThat(json.get("simulation").isNull()).isTrue();
  }

  @Test
  void fullyPopulatedPayloadMatchesTheSchema() throws IOException {
    assertThat(validator.validate(toJson(populatedSnapshot()))).isEmpty();
  }

  @Test
  void validatorReportsAMissingRequiredField() throws IOException {
    JsonNode json = toJson(populatedSnapshot());
    ((ObjectNode) json.get("caches").get(0)).remove("hitRate");

    assertThat(validator.validate(json))
        .hasValue("$.caches[0]: missing required property 'hitRate'");
  }

  @Test
  void validatorReportsAnExtraField() throws IOException {
    JsonNode json = toJson(populatedSnapshot());
    ((ObjectNode) json.get("caches").get(0)).put("bogus", 1);

    assertThat(validator.validate(json))
        .hasValueSatisfying(v -> assertThat(v).startsWith("$.caches[0].bogus: "));
  }

  @Test
  void validatorReportsRangeTypeAndSizeViolations() throws IOException {
    JsonNode json = toJson(populatedSnapshot());
    ((ObjectNode) json.get("caches").get(0)).put("hitRate", 1.2);
    assertThat(validator.validate(json))
        .hasValueSatisfying(v -> assertThat(v).startsWith("$.caches[0].hitRate: 1.2 is above"));

    JsonNode nullTs = toJson(populatedSnapshot());
    ((ObjectNode) nullTs).putNull("ts");
    assertThat(validator.validate(nullTs))
        .hasValueSatisfying(v -> assertThat(v).startsWith("$.ts: expected type [integer]"));

    JsonNode tooManyEvents = toJson(populatedSnapshot());
    ArrayNode events = (ArrayNode) tooManyEvents.get("events");
    for (int i = 0; i < MetricsSnapshot.MAX_EVENTS; i++) {
      events.add(events.get(0).deepCopy());
    }
    assertThat(validator.validate(tooManyEvents))
        .hasValueSatisfying(v -> assertThat(v).startsWith("$.events: 51 items exceed maxItems"));
  }

  @Test
  void validatorRejectsKeywordsItDoesNotImplement() {
    ObjectNode schema = objectMapper.createObjectNode().put("type", "string").put("pattern", "x");
    MiniJsonSchemaValidator strict = new MiniJsonSchemaValidator(schema);

    assertThatThrownBy(() -> strict.validate(objectMapper.getNodeFactory().textNode("x")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pattern");
  }

  private JsonNode toJson(MetricsSnapshot snapshot) throws IOException {
    return objectMapper.readTree(objectMapper.writeValueAsString(snapshot));
  }

  /** A payload with every nullable field set, as the real publisher will send in Step 3+. */
  private static MetricsSnapshot populatedSnapshot() {
    CacheMetrics cache =
        new CacheMetrics(
            "lru-A",
            "demo",
            PolicyType.LRU,
            1000,
            1000,
            812_344,
            190_211,
            0.81,
            0.774,
            180_102,
            9120,
            5012,
            0.8,
            6.4,
            812_344,
            9_748_128,
            40.62);
    GroupMetrics group =
        new GroupMetrics(
            "demo",
            List.of("lru-A", "lfu-A"),
            0.861,
            new AdvisorRecommendation(PolicyType.LRU, PolicyType.LFU, 7.4, 30));
    SimulationStatus simulation =
        new SimulationStatus(
            "s-12",
            true,
            "demo",
            Pattern.SCAN_POLLUTION,
            1,
            1,
            3,
            "A one-off scan floods the cache",
            1_790_000_000_000L);
    RemovalEvent event =
        new RemovalEvent(1_790_000_000_400L, "lru-A", "drug:4411", RemovalCause.EXPIRED);
    return new MetricsSnapshot(
        1_790_000_000_500L, List.of(cache), List.of(group), simulation, List.of(event));
  }
}

package com.acentra.catchy.telemetry.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.acentra.cache.CacheAction;
import com.acentra.cache.EventSeverity;
import com.acentra.cache.EvictionPolicy;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.cache.telemetry.TelemetryJson;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngestUnitTest {

    @Test
    void scannerReportsEveryUndeclaredPropertyByPathAndNeverItsValue() throws Exception {
        JsonNode root = TelemetryJson.mapper().readTree("""
                {"applicationName":"a","environment":"e","instanceId":"i","secretToken":"hunter2",
                 "events":[{"cacheRegion":"r","rawKey":"member-1"},{"cacheRegion":"r","value":"phi"}],
                 "snapshots":[{"cacheRegion":"r","recentWindow":{"minutes":10,"memberId":"m"},"shadow":{"requests":1,"claimId":"c"},"extra":1}]}
                """);
        List<String> unknown = UnknownFieldScanner.scanBatch(root);
        assertThat(unknown).containsExactlyInAnyOrder("secretToken", "events[0].rawKey", "events[1].value",
                "snapshots[0].extra", "snapshots[0].recentWindow.memberId", "snapshots[0].shadow.claimId");
        assertThat(String.join(",", unknown)).doesNotContain("hunter2").doesNotContain("member-1").doesNotContain("phi");
    }

    @Test
    void scannerAcceptsExactlyTheSdkWireShape() throws Exception {
        var event = new TelemetryEvent(Instant.now(), "a", "e", "r", CacheAction.HIT, null, "x", EvictionPolicy.LRU, 0, 0, 0, 0, 0, 0, 0, 0,
                true, EventSeverity.INFO, 0);
        String json = TelemetryJson.mapper().writeValueAsString(event);
        assertThat(UnknownFieldScanner.scanEvent(TelemetryJson.mapper().readTree(json))).isEmpty();
    }

    @Test
    void clientControlledFieldNamesAreSanitizedAndCapped() {
        assertThat(UnknownFieldScanner.sanitize("patient John Doe!")).isEqualTo("patient?John?Doe?");
        assertThat(UnknownFieldScanner.sanitize("a".repeat(200))).hasSize(60);
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) many.add("f" + i);
        List<String> capped = UnknownFieldScanner.capped(many);
        assertThat(capped).hasSize(11);
        assertThat(capped.get(10)).isEqualTo("... and 15 more");
    }

    @Test
    void validatorNamesFieldsAndRulesWithoutEchoingValues() {
        var limits = new CatchyProperties.Ingest(1_048_576, 65_536, 2, 200, 600);
        var bad = new TelemetryEvent(null, null, null, "Bad Region", null, "raw-member-key", "x".repeat(401), null, -1, 0, 0, 0, 0, 0, 0, 0,
                false, null, -0.5);
        List<String> errors = new ArrayList<>();
        TelemetryValidator.validateEvent(bad, "events[0].", errors);
        assertThat(errors).contains("events[0].timestamp: must not be null", "events[0].action: must not be null",
                "events[0].policy: must not be null");
        assertThat(String.join("|", errors)).contains("events[0].cacheRegion").contains("events[0].keyFingerprint")
                .contains("events[0].reason").contains("events[0].frequency").contains("events[0].latencyMs")
                .doesNotContain("raw-member-key").doesNotContain("Bad Region");
        var tooMany = new com.acentra.cache.telemetry.TelemetryBatch(null, null, "i", null, null,
                List.of(bad, bad, bad), List.of());
        assertThat(TelemetryValidator.validateBatch(tooMany, limits)).containsExactly("events: must contain at most 2 items");
    }
}

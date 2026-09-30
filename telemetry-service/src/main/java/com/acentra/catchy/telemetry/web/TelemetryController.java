package com.acentra.catchy.telemetry.web;

import com.acentra.cache.telemetry.ControlResponse;
import com.acentra.cache.telemetry.RegionSnapshot;
import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.catchy.telemetry.service.RecommendationService;
import com.acentra.catchy.telemetry.dto.OpsDtos.IngestAck;
import com.acentra.catchy.telemetry.ingest.ControlService;
import com.acentra.catchy.telemetry.ingest.IngestionService;
import com.acentra.catchy.telemetry.ingest.TelemetryRequestParser;
import com.acentra.catchy.telemetry.security.IngestPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDK-facing endpoints, authenticated by API key. Bodies are bound by {@link TelemetryRequestParser}, which rejects
 * unknown properties (privacy guard). Request bodies are never logged.
 */
@RestController
@RequestMapping("/api/v1/telemetry")
public class TelemetryController {

    private final TelemetryRequestParser parser;
    private final IngestionService ingestion;
    private final ControlService control;
    private final RecommendationService recommendations;

    public TelemetryController(TelemetryRequestParser parser, IngestionService ingestion, ControlService control,
                               RecommendationService recommendations) {
        this.parser = parser;
        this.ingestion = ingestion;
        this.control = control;
        this.recommendations = recommendations;
    }

    @PostMapping(value = "/events", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAck event(@RequestBody byte[] body, @AuthenticationPrincipal IngestPrincipal principal) {
        return ingestion.ingestEvent(principal, parser.parseEvent(body, principal));
    }

    @PostMapping(value = "/events/batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public IngestAck batch(@RequestBody byte[] body, @AuthenticationPrincipal IngestPrincipal principal) {
        TelemetryBatch batch = parser.parseBatch(body, principal);
        IngestAck ack = ingestion.ingest(principal, batch);
        if (batch.snapshots() != null && !batch.snapshots().isEmpty()) {
            // first appearance of a region: store its (deterministic) recommendation right away; never calls Ollama
            recommendations.ensureStored(principal.applicationId(),
                    batch.snapshots().stream().map(RegionSnapshot::cacheRegion).distinct().toList());
        }
        return ack;
    }

    @GetMapping("/control")
    public ControlResponse control(@AuthenticationPrincipal IngestPrincipal principal) {
        return control.controlFor(principal.applicationId());
    }
}

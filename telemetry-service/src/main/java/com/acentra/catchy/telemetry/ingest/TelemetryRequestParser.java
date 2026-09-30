package com.acentra.catchy.telemetry.ingest;

import com.acentra.cache.telemetry.TelemetryBatch;
import com.acentra.cache.telemetry.TelemetryEvent;
import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.security.IngestPrincipal;
import com.acentra.catchy.telemetry.service.AuditAction;
import com.acentra.catchy.telemetry.service.AuditService;
import com.acentra.catchy.telemetry.web.ApiException;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Strict parser for SDK telemetry. Implements the privacy guard: any property that the wire records do not declare is
 * rejected with 400 (field names only) and audited as TELEMETRY_REJECTED, and nothing from the payload is stored.
 * Bound values are then validated per docs/api.md.
 */
@Component
public class TelemetryRequestParser {

    private final JsonMapper strict;
    private final CatchyProperties.Ingest limits;
    private final AuditService audit;

    public TelemetryRequestParser(CatchyProperties props, AuditService audit) {
        this.limits = props.ingest();
        this.audit = audit;
        JsonFactory factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder().maxStringLength(100_000).maxNestingDepth(20).build())
                .build();
        this.strict = JsonMapper.builder(factory)
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
                .build();
    }

    public TelemetryBatch parseBatch(byte[] body, IngestPrincipal principal) {
        JsonNode root = readTree(body);
        rejectUnknown(UnknownFieldScanner.scanBatch(root), principal);
        TelemetryBatch batch = bind(root, TelemetryBatch.class);
        List<String> errors = TelemetryValidator.validateBatch(batch, limits);
        if (!errors.isEmpty()) throw ApiException.badRequest("Validation failed", errors);
        return batch;
    }

    public TelemetryEvent parseEvent(byte[] body, IngestPrincipal principal) {
        JsonNode root = readTree(body);
        rejectUnknown(UnknownFieldScanner.scanEvent(root), principal);
        TelemetryEvent event = bind(root, TelemetryEvent.class);
        List<String> errors = TelemetryValidator.validateSingleEvent(event);
        if (!errors.isEmpty()) throw ApiException.badRequest("Validation failed", errors);
        return event;
    }

    private JsonNode readTree(byte[] body) {
        try {
            JsonNode root = body == null || body.length == 0 ? null : strict.readTree(body);
            if (root == null || root.isMissingNode() || !root.isObject()) {
                throw ApiException.badRequest("Request body must be a JSON object");
            }
            return root;
        } catch (IOException e) {
            throw ApiException.badRequest("Malformed JSON");
        }
    }

    private <T> T bind(JsonNode root, Class<T> type) {
        try {
            return strict.treeToValue(root, type);
        } catch (JsonMappingException e) {
            StringBuilder path = new StringBuilder();
            for (JsonMappingException.Reference ref : e.getPath()) {
                if (ref.getFieldName() != null) {
                    if (!path.isEmpty()) path.append('.');
                    path.append(ref.getFieldName());
                } else if (ref.getIndex() >= 0) {
                    path.append('[').append(ref.getIndex()).append(']');
                }
            }
            String field = path.isEmpty() ? "body" : UnknownFieldScanner.sanitize(path.toString());
            throw ApiException.badRequest("Validation failed", List.of(field + ": invalid value or type"));
        } catch (JsonProcessingException e) {
            throw ApiException.badRequest("Malformed JSON");
        }
    }

    private void rejectUnknown(List<String> unknown, IngestPrincipal principal) {
        if (unknown.isEmpty()) return;
        List<String> names = UnknownFieldScanner.capped(unknown);
        audit.denied(AuditAction.TELEMETRY_REJECTED, "TELEMETRY", null, principal.applicationId(),
                "Rejected telemetry payload: unknown field(s) not allowed: " + String.join(", ", names));
        throw ApiException.badRequest("Telemetry payload rejected: it contains fields that are not part of the safe telemetry schema",
                names.stream().map(n -> n + ": unknown field is not allowed").toList());
    }
}

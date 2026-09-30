package com.acentra.catchy.demo.eligibility;

import com.acentra.cache.CacheLoadException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Privacy-safe error bodies: never echoes ids, request bodies or exception messages that may contain values. */
@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(InvalidRequestException.class)
    ResponseEntity<Map<String, Object>> invalid(InvalidRequestException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", e.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> invalidBody(MethodArgumentNotValidException e) {
        List<String> fields = e.getBindingResult().getFieldErrors().stream().map(f -> f.getField()).distinct().toList();
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is invalid (e.g. requests must be within the allowed bounds).", fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Request body is not valid JSON.", null);
    }

    @ExceptionHandler(CacheLoadException.class)
    ResponseEntity<Map<String, Object>> loadFailed(CacheLoadException e) {
        LOG.warn("Source load failed ({}).", e.getClass().getSimpleName());
        return body(HttpStatus.BAD_GATEWAY, "SOURCE_UNAVAILABLE", "The simulated source could not be loaded.", null);
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String error, String message, List<String> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", status.value());
        m.put("error", error);
        m.put("message", message);
        if (fields != null) m.put("fields", fields);
        return ResponseEntity.status(status).body(m);
    }
}

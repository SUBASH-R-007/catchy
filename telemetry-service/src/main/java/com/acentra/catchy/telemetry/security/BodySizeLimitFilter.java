package com.acentra.catchy.telemetry.security;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.web.RestErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Enforces request body limits before anything reads the body: 1 MB for telemetry ingestion, a small limit for all
 * other endpoints. Rejects with 413 using the contract error shape. Bodies are buffered (bounded) and replayed.
 */
public class BodySizeLimitFilter extends OncePerRequestFilter {

    private final CatchyProperties.Ingest limits;
    private final RestErrorWriter errors;

    public BodySizeLimitFilter(CatchyProperties props, RestErrorWriter errors) {
        this.limits = props.ingest();
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        return !(m.equals("POST") || m.equals("PUT") || m.equals("PATCH") || m.equals("DELETE"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        int limit = request.getRequestURI().startsWith("/api/v1/telemetry/") ? limits.maxBodyBytes() : limits.maxOtherBodyBytes();
        if (request.getContentLengthLong() > limit) {
            errors.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large (limit " + limit + " bytes)");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(limit + 1);
        if (body.length > limit) {
            errors.write(request, response, HttpStatus.PAYLOAD_TOO_LARGE, "Request body too large (limit " + limit + " bytes)");
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }
}

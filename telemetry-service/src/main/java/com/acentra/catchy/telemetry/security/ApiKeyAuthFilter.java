package com.acentra.catchy.telemetry.security;

import com.acentra.catchy.telemetry.service.ApiKeyService;
import com.acentra.catchy.telemetry.web.RestErrorWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates SDK calls on {@code /api/v1/telemetry/**} by the {@code X-AcentraCache-Key} header and applies the
 * per-key rate limit. An absent, unknown or revoked key leaves the request unauthenticated (401 from the entry point).
 * The key is never logged.
 */
public class ApiKeyAuthFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-AcentraCache-Key";

    private final ApiKeyService apiKeys;
    private final RateLimiter rateLimiter;
    private final RestErrorWriter errors;

    public ApiKeyAuthFilter(ApiKeyService apiKeys, RateLimiter rateLimiter, RestErrorWriter errors) {
        this.apiKeys = apiKeys;
        this.rateLimiter = rateLimiter;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/telemetry/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String presented = request.getHeader(HEADER);
        if (presented != null) {
            Optional<IngestPrincipal> principal = apiKeys.authenticate(presented.trim());
            if (principal.isPresent()) {
                if (!rateLimiter.tryAcquire(principal.get().apiKeyId())) {
                    response.setHeader("Retry-After", String.valueOf(rateLimiter.secondsUntilReset()));
                    errors.write(request, response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded for this API key");
                    return;
                }
                SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                        principal.get(), null, List.of(new SimpleGrantedAuthority("ROLE_INGEST"))));
            }
        }
        chain.doFilter(request, response);
    }
}

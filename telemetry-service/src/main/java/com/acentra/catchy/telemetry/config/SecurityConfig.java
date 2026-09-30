package com.acentra.catchy.telemetry.config;

import com.acentra.catchy.telemetry.security.ApiKeyAuthFilter;
import com.acentra.catchy.telemetry.security.BearerTokenFilter;
import com.acentra.catchy.telemetry.security.BodySizeLimitFilter;
import com.acentra.catchy.telemetry.security.RateLimiter;
import com.acentra.catchy.telemetry.security.TokenService;
import com.acentra.catchy.telemetry.service.ApiKeyService;
import com.acentra.catchy.telemetry.service.AuditAction;
import com.acentra.catchy.telemetry.service.AuditService;
import com.acentra.catchy.telemetry.web.RestErrorWriter;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless security: dashboard users by bearer token, SDK instances by API key (telemetry endpoints only).
 * Minimum roles per endpoint follow docs/api.md; ADMIN holds every lower role's authority, so each rule names only
 * the minimum role. Any mutating endpoint that is not listed explicitly falls back to ADMIN.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, CatchyProperties props, TokenService tokens,
                                                   ApiKeyService apiKeys, RateLimiter rateLimiter,
                                                   RestErrorWriter errors, AuditService audit,
                                                   @Qualifier("corsConfigurationSource") CorsConfigurationSource corsSource)
            throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, ex) -> errors.write(request, response,
                HttpStatus.UNAUTHORIZED,
                request.getRequestURI().startsWith("/api/v1/telemetry/")
                        ? "Missing, invalid or revoked API key" : "Authentication required");

        AccessDeniedHandler deniedHandler = (request, response, ex) -> {
            String target = request.getMethod() + " " + request.getRequestURI();
            audit.denied(AuditAction.ACCESS_DENIED, "ENDPOINT", target, null,
                    "Denied " + target + " for role " + audit.currentActor().role());
            errors.write(request, response, HttpStatus.FORBIDDEN, "Access denied: insufficient role");
        };

        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsSource))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .logout(l -> l.disable())
                .requestCache(rc -> rc.disable())
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint).accessDeniedHandler(deniedHandler))
                .headers(h -> h.contentTypeOptions(Customizer.withDefaults()).frameOptions(f -> f.deny()))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/health", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login", "/api/v1/auth/demo-login").permitAll()
                        // SDK ingestion: API key only
                        .requestMatchers("/api/v1/telemetry/**").hasRole("INGEST")
                        // ADMIN
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/v1/projects/*/applications").hasRole("ADMIN")
                        .requestMatchers("/api/v1/applications/*/api-keys").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/v1/api-keys/*").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/v1/applications/*/regions/*/config").hasRole("ADMIN")
                        .requestMatchers("/api/v1/audit-logs").hasRole("ADMIN")
                        // ENGINEER
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/simulate/*").hasRole("ENGINEER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/recommendations/evaluate").hasRole("ENGINEER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/applications/*/policy-change-requests").hasRole("ENGINEER")
                        .requestMatchers(HttpMethod.POST, "/api/v1/policy-change-requests/*/approve",
                                "/api/v1/policy-change-requests/*/reject").hasRole("ENGINEER")
                        // VIEWER: every other read
                        .requestMatchers(HttpMethod.GET, "/api/v1/**").hasRole("VIEWER")
                        // default deny for anything mutating that was not listed above
                        .requestMatchers("/api/v1/**").hasRole("ADMIN")
                        .anyRequest().denyAll())
                .addFilterBefore(new BodySizeLimitFilter(props, errors), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new ApiKeyAuthFilter(apiKeys, rateLimiter, errors), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new BearerTokenFilter(tokens), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    /** CORS is restricted to the dashboard origin(s) from CATCHY_DASHBOARD_ORIGIN (comma separated). */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(CatchyProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        List<String> origins = Arrays.stream(props.dashboardOrigin().split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
        cfg.setAllowedOrigins(origins);
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        cfg.setExposedHeaders(List.of("Retry-After"));
        cfg.setAllowCredentials(false);
        cfg.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}

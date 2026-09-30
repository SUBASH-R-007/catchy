package com.acentra.catchy.telemetry.dto;

import com.acentra.catchy.telemetry.security.Role;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** Authentication request/response shapes (docs/api.md "Authentication"). */
public final class AuthDtos {
    private AuthDtos() {}

    public record LoginRequest(@NotBlank @Size(max = 64) String username, @NotBlank @Size(max = 200) String password) {}

    public record DemoLoginRequest(@NotNull Role role) {}

    public record LoginResponse(String token, String username, Role role, Instant expiresAt) {}

    public record Me(String username, Role role) {}

    public record HealthResponse(String status, String service, String version, boolean demoMode) {}
}

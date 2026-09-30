package com.acentra.catchy.telemetry.web;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import com.acentra.catchy.telemetry.dto.AuthDtos.DemoLoginRequest;
import com.acentra.catchy.telemetry.dto.AuthDtos.HealthResponse;
import com.acentra.catchy.telemetry.dto.AuthDtos.LoginRequest;
import com.acentra.catchy.telemetry.dto.AuthDtos.LoginResponse;
import com.acentra.catchy.telemetry.dto.AuthDtos.Me;
import com.acentra.catchy.telemetry.security.AuthenticatedUser;
import com.acentra.catchy.telemetry.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class AuthController {

    private final AuthService auth;
    private final CatchyProperties props;
    private final String version;

    public AuthController(AuthService auth, CatchyProperties props, @Value("${catchy.version:1.0.0}") String version) {
        this.auth = auth;
        this.props = props;
        this.version = version;
    }

    @GetMapping("/health")
    public HealthResponse health() {
        return new HealthResponse("UP", "telemetry-service", version, props.demoMode());
    }

    @PostMapping("/auth/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest body) {
        return auth.login(body.username(), body.password());
    }

    /** 404 unless CATCHY_DEMO_MODE=true. */
    @PostMapping("/auth/demo-login")
    public LoginResponse demoLogin(@Valid @RequestBody DemoLoginRequest body) {
        return auth.demoLogin(body.role());
    }

    @GetMapping("/auth/me")
    public Me me(@AuthenticationPrincipal AuthenticatedUser user) {
        return new Me(user.username(), user.role());
    }
}

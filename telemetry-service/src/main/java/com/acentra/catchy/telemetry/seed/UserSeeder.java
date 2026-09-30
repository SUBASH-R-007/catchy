package com.acentra.catchy.telemetry.seed;

import com.acentra.catchy.telemetry.service.AuthService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Seeds the admin / engineer / viewer users at startup (idempotent). */
@Component
@Order(1)
public class UserSeeder implements ApplicationRunner {

    private final AuthService auth;

    public UserSeeder(AuthService auth) {
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        auth.seedUsers();
    }
}

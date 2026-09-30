package com.acentra.catchy.demo.claims;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Synthetic claims-processing demo application. All data is synthetic; no real patient data is ever used. */
@SpringBootApplication
public class ClaimsServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ClaimsServiceApplication.class, args);
    }
}

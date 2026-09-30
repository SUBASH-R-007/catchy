package com.acentra.catchy.demo.eligibility;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Synthetic eligibility-verification demo application. All data is synthetic; no real patient data is ever used. */
@SpringBootApplication
public class EligibilityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(EligibilityServiceApplication.class, args);
    }
}

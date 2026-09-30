package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/** Guards the "no secrets in configuration" rule for the shipped YAML files. */
class ConfigFilesTest {

    private static String read(String name) throws IOException {
        try (InputStream in = new ClassPathResource(name).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void shippedConfigurationOnlyUsesEnvironmentPlaceholdersForSecrets() throws Exception {
        Pattern secretLine = Pattern.compile("(?im)^\\s*[\\w.-]*(password|secret|api-key|apikey|token)[\\w.-]*\\s*:\\s*(.*)$");
        for (String file : List.of("application.yml", "application-postgres.yml")) {
            Matcher m = secretLine.matcher(read(file));
            while (m.find()) {
                String key = m.group(0).trim();
                String value = m.group(2).trim();
                // empty, an environment placeholder, or the non-secret token lifetime (ISO-8601 duration)
                boolean safe = value.isEmpty() || value.equals("\"\"") || value.startsWith("${") || value.matches("^PT\\d+[HMS]$");
                assertThat(safe).as("%s: '%s' must be empty or an environment placeholder", file, key).isTrue();
            }
        }
    }

    @Test
    void postgresProfileReadsConnectionDetailsFromTheDocumentedVariables() throws Exception {
        String yml = read("application-postgres.yml");
        assertThat(yml).contains("${CATCHY_DB_URL:jdbc:postgresql://localhost:5433/catchy}")
                .contains("${CATCHY_DB_USER:").contains("${CATCHY_DB_PASSWORD:}");
    }

    @Test
    void localProfileIsTheDefaultAndUsesH2InPostgresMode() throws Exception {
        String yml = read("application.yml");
        assertThat(yml).contains("default: local").contains("MODE=PostgreSQL").contains("port: 8090")
                .contains("ddl-auto: validate").contains("open-in-view: false");
    }
}

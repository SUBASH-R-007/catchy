package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.acentra.catchy.telemetry.security.ApiKeys;
import com.acentra.catchy.telemetry.seed.DataSeeder;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Idempotent demo seeding with a key supplied through the environment (registered hashed, never generated). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "catchy.seed.enabled=true",
        "catchy.seed.claims-api-key=acc_c1a10001_7f3e9b2d4a6c81e0f5d7b3a9c2e4f601",
        "spring.datasource.url=jdbc:h2:mem:catchy-seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class SeedingTest {
    private static final String CLAIMS_KEY = "acc_c1a10001_7f3e9b2d4a6c81e0f5d7b3a9c2e4f601";

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MockMvc mvc;
    @Autowired
    DataSeeder seeder;

    @Test
    void seedsProjectsApplicationsAndTheSuppliedKeyOnlyOnceAndHashed() throws Exception {
        seeder.run(null); // a second run must change nothing

        List<String> projects = jdbc.queryForList("select name from project order by id", String.class);
        assertThat(projects).containsExactly("Claims Platform", "Eligibility Platform", "Prior Authorization Platform");
        List<Map<String, Object>> apps = jdbc.queryForList("select id, name, environment, project_id from application_service order by id");
        assertThat(apps).hasSize(2);
        assertThat(apps.get(0).get("name")).isEqualTo("claims-service");
        assertThat(apps.get(0).get("environment")).isEqualTo("staging");
        assertThat(((Number) apps.get(0).get("id")).longValue()).isEqualTo(1L);
        assertThat(apps.get(1).get("name")).isEqualTo("eligibility-service");

        // only the supplied key exists, stored hashed; no key is invented for eligibility-service
        List<Map<String, Object>> keys = jdbc.queryForList("select application_id, key_prefix, key_hash from application_api_key");
        assertThat(keys).hasSize(1);
        assertThat(((Number) keys.get(0).get("application_id")).longValue()).isEqualTo(1L);
        assertThat(keys.get(0).get("key_hash")).isEqualTo(ApiKeys.hash(CLAIMS_KEY));
        assertThat(keys.get(0).get("key_prefix")).isEqualTo("acc_c1a10001");
        assertThat(keys.toString()).doesNotContain(CLAIMS_KEY);

        String body = "{\"instanceId\":\"seed-check\",\"events\":[],\"snapshots\":[]}";
        mvc.perform(post("/api/v1/telemetry/events/batch").header("X-AcentraCache-Key", CLAIMS_KEY)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isAccepted());
    }
}

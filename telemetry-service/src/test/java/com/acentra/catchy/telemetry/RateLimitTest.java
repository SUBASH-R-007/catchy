package com.acentra.catchy.telemetry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
        "catchy.ingest.rate-limit-per-minute=5",
        "spring.datasource.url=jdbc:h2:mem:catchy-ratelimit;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1"})
class RateLimitTest extends AbstractApiTest {

    @Test
    void perKeyRateLimitReturns429WithoutAffectingOtherKeys() throws Exception {
        TestApp first = createApp("claims-service");
        TestApp second = createApp("eligibility-service");
        String body = sdkJson.writeValueAsString(TestData.batch(null, null, "i-1", List.of(), List.of()));

        // invalid keys never consume a valid key's budget
        for (int i = 0; i < 10; i++) ingestRaw("acc_ffffffff_ffffffffffffffffffffffffffffffff", body).andExpect(status().isUnauthorized());

        // the window is per minute; retry once if the test happens to straddle a minute boundary
        int accepted = 0;
        int limited = 0;
        for (int i = 0; i < 8; i++) {
            int code = ingestRaw(first.apiKey(), body).andReturn().getResponse().getStatus();
            if (code == 202) accepted++;
            if (code == 429) limited++;
        }
        assertThat(accepted).isBetween(5, 9);
        assertThat(limited).isGreaterThanOrEqualTo(1);
        ingestRaw(first.apiKey(), body).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("Too Many Requests"));

        // another application's key has its own budget
        ingestRaw(second.apiKey(), body).andExpect(status().isAccepted());
    }
}

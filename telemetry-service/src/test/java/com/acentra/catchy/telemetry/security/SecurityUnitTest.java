package com.acentra.catchy.telemetry.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecurityUnitTest {

    private static CatchyProperties props(String secret, int rateLimit) {
        return new CatchyProperties(false, "http://localhost:5173",
                new CatchyProperties.Security(secret, Duration.ofHours(8), "", "", ""),
                new CatchyProperties.Ingest(1_048_576, 65_536, 500, 200, rateLimit), null, null, null, null, null, null);
    }

    @Test
    void tokenRoundTripAndExpiry() {
        Instant start = Instant.parse("2026-09-30T09:00:00Z");
        TokenService issuer = new TokenService(props("a-long-random-secret-for-tests-0123456789", 10), Clock.fixed(start, ZoneOffset.UTC));
        TokenService.IssuedToken token = issuer.issue("engineer", Role.ENGINEER);
        assertThat(token.expiresAt()).isEqualTo(start.plus(Duration.ofHours(8)));
        assertThat(issuer.verify(token.token())).contains(new AuthenticatedUser("engineer", Role.ENGINEER));

        TokenService sevenHoursLater = new TokenService(props("a-long-random-secret-for-tests-0123456789", 10),
                Clock.fixed(start.plus(Duration.ofHours(7)), ZoneOffset.UTC));
        assertThat(sevenHoursLater.verify(token.token())).isPresent();
        TokenService nineHoursLater = new TokenService(props("a-long-random-secret-for-tests-0123456789", 10),
                Clock.fixed(start.plus(Duration.ofHours(9)), ZoneOffset.UTC));
        assertThat(nineHoursLater.verify(token.token())).as("expired after 8 h").isEmpty();
    }

    @Test
    void tokensFromAnotherSecretMalformedAndTamperedTokensAreRejected() {
        Clock clock = Clock.systemUTC();
        TokenService a = new TokenService(props("secret-number-one-0123456789-abcdefghij", 10), clock);
        TokenService b = new TokenService(props("secret-number-two-0123456789-abcdefghij", 10), clock);
        String token = a.issue("admin", Role.ADMIN).token();
        assertThat(b.verify(token)).isEmpty();
        for (String bad : new String[] {null, "", ".", "abc", "a.b", token + "x", token.replace('.', ':'), "x".repeat(600)}) {
            assertThat(a.verify(bad)).as("token %s", bad == null ? "null" : bad.length() > 20 ? "long" : bad).isEmpty();
        }
    }

    @Test
    void blankSecretGeneratesAnEphemeralOneThatIsDifferentPerInstance() {
        Clock clock = Clock.systemUTC();
        TokenService a = new TokenService(props("", 10), clock);
        TokenService b = new TokenService(props("", 10), clock);
        String token = a.issue("viewer", Role.VIEWER).token();
        assertThat(a.verify(token)).isPresent();
        assertThat(b.verify(token)).isEmpty();
    }

    @Test
    void roleAuthoritiesIncludeEveryLowerRole() {
        assertThat(Role.ADMIN.authorities()).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_VIEWER", "ROLE_ENGINEER", "ROLE_ADMIN");
        assertThat(Role.ENGINEER.authorities()).extracting(Object::toString).containsExactlyInAnyOrder("ROLE_VIEWER", "ROLE_ENGINEER");
        assertThat(Role.VIEWER.authorities()).extracting(Object::toString).containsExactly("ROLE_VIEWER");
    }

    @Test
    void apiKeysHaveTheDocumentedFormatAreUniqueAndOnlyTheirHashMatches() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String key = ApiKeys.generate();
            assertThat(key).matches("^acc_[0-9a-f]{8}_[0-9a-f]{32}$");
            assertThat(ApiKeys.isWellFormed(key)).isTrue();
            assertThat(seen.add(key)).isTrue();
        }
        String key = ApiKeys.generate();
        String hash = ApiKeys.hash(key);
        assertThat(hash).hasSize(64).doesNotContain(key);
        assertThat(ApiKeys.matches(hash, key)).isTrue();
        assertThat(ApiKeys.matches(hash, ApiKeys.generate())).isFalse();
        assertThat(ApiKeys.prefixOf(key)).hasSize(12).isEqualTo(key.substring(0, 12));
        assertThat(ApiKeys.mask("acc_ab12cd34")).isEqualTo("acc_ab12cd34_" + "•".repeat(16));
        assertThat(ApiKeys.isWellFormed("acc_ab12cd34_short")).isFalse();
        assertThat(ApiKeys.isWellFormed(null)).isFalse();
    }

    @Test
    void rateLimiterAllowsTheBudgetPerKeyAndPerMinuteWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-09-30T09:00:10Z"));
        RateLimiter limiter = new RateLimiter(props("s", 3), clock);
        for (int i = 0; i < 3; i++) assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
        assertThat(limiter.tryAcquire(2L)).as("separate key, separate budget").isTrue();
        assertThat(limiter.secondsUntilReset()).isEqualTo(50);
        clock.advance(Duration.ofSeconds(60));
        assertThat(limiter.tryAcquire(1L)).as("new minute, new budget").isTrue();
    }

    /** Minimal settable clock for window tests. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}

package com.acentra.catchy.telemetry.security;

import com.acentra.catchy.telemetry.config.CatchyProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Compact HMAC-SHA256 signed session tokens implemented with the JDK only:
 * {@code base64url(username|ROLE|expiryEpochSeconds) . base64url(hmac)}.
 * The signing secret comes from {@code CATCHY_TOKEN_SECRET}; when blank a random one is generated at startup
 * (tokens then reset on restart). The secret is never logged.
 */
@Service
public class TokenService {
    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    public record IssuedToken(String token, Instant expiresAt) {}

    private final byte[] secret;
    private final Duration ttl;
    private final Clock clock;

    public TokenService(CatchyProperties props, Clock clock) {
        this.clock = clock;
        this.ttl = props.security().tokenTtl();
        String configured = props.security().tokenSecret();
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            this.secret = random;
            log.warn("CATCHY_TOKEN_SECRET is not set: generated an ephemeral token signing secret. "
                    + "Dashboard sessions are invalidated whenever this service restarts.");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
            if (this.secret.length < 32) {
                log.warn("CATCHY_TOKEN_SECRET is shorter than 32 characters; use a longer random secret.");
            }
        }
    }

    public IssuedToken issue(String username, Role role) {
        Instant expires = Instant.now(clock).plus(ttl).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String payload = username + "|" + role.name() + "|" + expires.getEpochSecond();
        String body = ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return new IssuedToken(body + "." + ENC.encodeToString(hmac(body)), expires);
    }

    /** Returns the user when the token is well-formed, correctly signed and not expired. */
    public Optional<AuthenticatedUser> verify(String token) {
        if (token == null || token.length() > 512) return Optional.empty();
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) return Optional.empty();
        String body = token.substring(0, dot);
        try {
            byte[] presented = DEC.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(presented, hmac(body))) return Optional.empty();
            String[] parts = new String(DEC.decode(body), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3) return Optional.empty();
            long expiry = Long.parseLong(parts[2]);
            if (Instant.now(clock).getEpochSecond() >= expiry) return Optional.empty();
            return Optional.of(new AuthenticatedUser(parts[0], Role.valueOf(parts[1])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private byte[] hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }
}

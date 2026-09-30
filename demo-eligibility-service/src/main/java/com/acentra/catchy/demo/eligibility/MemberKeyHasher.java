package com.acentra.catchy.demo.eligibility;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

/**
 * Builds the cache keys of the sensitive regions: a salted SHA-256 (hex) of {@code eligibility:member:<id>} (or
 * {@code authorization:request:<id>}). The raw member / request id is never used as a cache key, so it is not stored in
 * the cache map. The SDK then fingerprints this key once more for telemetry.
 *
 * <p>The salt comes from {@code CATCHY_KEY_SALT}; when blank a random per-process salt is used. It is never logged.
 */
@Component
public class MemberKeyHasher {

    private final byte[] salt;

    public MemberKeyHasher(DemoProperties props) {
        String configured = props.getKeySalt();
        if (configured == null || configured.isBlank()) {
            byte[] b = new byte[16];
            new SecureRandom().nextBytes(b);
            this.salt = b;
        } else {
            this.salt = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String memberKey(String syntheticMemberId) {
        return hash("eligibility:member:" + syntheticMemberId);
    }

    public String authorizationKey(String syntheticRequestId) {
        return hash("authorization:request:" + syntheticRequestId);
    }

    private String hash(String logicalKey) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            md.update((byte) 0);
            md.update(logicalKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}

package com.acentra.catchy.telemetry.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** API key format, hashing and masking. Plaintext keys are never stored or logged. */
public final class ApiKeys {
    public static final Pattern FORMAT = Pattern.compile("^acc_[0-9a-f]{8}_[0-9a-f]{32}$");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final HexFormat HEX = HexFormat.of();

    private ApiKeys() {}

    /** {@code acc_<8 hex>_<32 hex>}. */
    public static String generate() {
        byte[] prefix = new byte[4];
        byte[] secret = new byte[16];
        RANDOM.nextBytes(prefix);
        RANDOM.nextBytes(secret);
        return "acc_" + HEX.formatHex(prefix) + "_" + HEX.formatHex(secret);
    }

    public static boolean isWellFormed(String key) {
        return key != null && key.length() == 45 && FORMAT.matcher(key).matches();
    }

    /** The non-secret identifying prefix, e.g. {@code acc_ab12cd34}. */
    public static String prefixOf(String key) {
        return key.substring(0, 12);
    }

    public static String hash(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HEX.formatHex(md.digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of the stored hash with the hash of a presented key. */
    public static boolean matches(String storedHash, String presentedKey) {
        byte[] a = storedHash.getBytes(StandardCharsets.UTF_8);
        byte[] b = hash(presentedKey).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    public static String mask(String keyPrefix) {
        return keyPrefix + "_" + "•".repeat(16);
    }
}

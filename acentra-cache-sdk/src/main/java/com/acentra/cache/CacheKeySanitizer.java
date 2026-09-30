package com.acentra.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Turns raw cache keys into telemetry-safe fingerprints. The raw key never leaves the process.
 * <ul>
 *   <li>{@link Mode#HASHED}: {@code sha256:<hex>} of (salt, region, key), truncated to {@code hexLength} chars.</li>
 *   <li>{@link Mode#CATEGORY_ONLY}: no fingerprint at all ({@code null}); only the region is reported.</li>
 * </ul>
 */
public final class CacheKeySanitizer {

    public enum Mode { HASHED, CATEGORY_ONLY }

    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    });

    private final byte[] salt;
    private final Mode mode;
    private final int hexLength;

    public CacheKeySanitizer(String salt, Mode mode) {
        this(salt, mode, 16);
    }

    public CacheKeySanitizer(String salt, Mode mode, int hexLength) {
        Objects.requireNonNull(mode, "mode");
        if (hexLength < 8 || hexLength > 64) throw new IllegalArgumentException("hexLength must be 8..64");
        this.salt = (salt == null ? "" : salt).getBytes(StandardCharsets.UTF_8);
        this.mode = mode;
        this.hexLength = hexLength;
    }

    /** Random per-process salt: fingerprints are not comparable across restarts. */
    public static CacheKeySanitizer withRandomSalt() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        return new CacheKeySanitizer(HexFormat.of().formatHex(b), Mode.HASHED);
    }

    public Mode mode() {
        return mode;
    }

    /** @return {@code "sha256:<hex>"}, or {@code null} in category-only mode. */
    public String fingerprint(String region, Object key) {
        if (mode == Mode.CATEGORY_ONLY) return null;
        MessageDigest md = DIGEST.get();
        md.reset();
        md.update(salt);
        md.update((byte) 0);
        md.update(String.valueOf(region).getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        md.update(String.valueOf(key).getBytes(StandardCharsets.UTF_8));
        return "sha256:" + HexFormat.of().formatHex(md.digest(), 0, hexLength / 2);
    }

    @Override
    public String toString() {
        return "CacheKeySanitizer[mode=" + mode + ", salt=***]";
    }
}

package com.acentra.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CacheKeySanitizerTest {

    @Test
    void fingerprintDoesNotExposeRawKey() {
        var s = new CacheKeySanitizer("app-salt", CacheKeySanitizer.Mode.HASHED);
        String fp = s.fingerprint("eligibility-summary", "eligibility:member:987654321");
        assertThat(fp).startsWith("sha256:").matches("sha256:[0-9a-f]{16}");
        assertThat(fp).doesNotContain("987654321").doesNotContain("eligibility");
    }

    @Test
    void fingerprintIsStableForSameInputAndDiffersBySaltRegionAndKey() {
        var a = new CacheKeySanitizer("salt-a", CacheKeySanitizer.Mode.HASHED);
        var b = new CacheKeySanitizer("salt-b", CacheKeySanitizer.Mode.HASHED);
        assertThat(a.fingerprint("r", "k")).isEqualTo(a.fingerprint("r", "k"));
        assertThat(a.fingerprint("r", "k")).isNotEqualTo(b.fingerprint("r", "k"));
        assertThat(a.fingerprint("r", "k")).isNotEqualTo(a.fingerprint("r2", "k"));
        assertThat(a.fingerprint("r", "k")).isNotEqualTo(a.fingerprint("r", "k2"));
    }

    @Test
    void categoryOnlyModeOmitsTheFingerprint() {
        var s = new CacheKeySanitizer("salt", CacheKeySanitizer.Mode.CATEGORY_ONLY);
        assertThat(s.fingerprint("eligibility-summary", "member-1")).isNull();
    }

    @Test
    void toStringDoesNotRevealSalt() {
        assertThat(new CacheKeySanitizer("super-secret-salt", CacheKeySanitizer.Mode.HASHED).toString()).doesNotContain("super-secret-salt");
    }

    @Test
    void fingerprintLengthIsConfigurable() {
        var s = new CacheKeySanitizer("x", CacheKeySanitizer.Mode.HASHED, 32);
        assertThat(s.fingerprint("r", "k")).matches("sha256:[0-9a-f]{32}");
    }

    @Test
    void randomSaltSanitizerWorks() {
        assertThat(CacheKeySanitizer.withRandomSalt().fingerprint("r", "k")).startsWith("sha256:");
    }
}

package com.aetherops.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 crypto cleanup (see STEP_REPORT.md): PasswordEncoderConfig was bumped from
 * BCrypt strength 10 (the no-arg default) to 12. The whole risk of that change is whether
 * passwords hashed under the old strength still authenticate — BCrypt embeds its own cost
 * factor in the hash (e.g. {@code $2a$10$...} vs {@code $2a$12$...}), so a strength-12
 * encoder must still be able to verify a strength-10 hash without rehashing it.
 */
class PasswordEncoderConfigTest {

    private final BCryptPasswordEncoder encoder = new PasswordEncoderConfig().passwordEncoder();

    @Test
    void passwordHashedAtOldStrengthTen_stillAuthenticatesUnderNewEncoder() {
        String rawPassword = "correct-horse-battery-staple";
        String hashedAtStrengthTen = new BCryptPasswordEncoder(10).encode(rawPassword);

        assertThat(encoder.matches(rawPassword, hashedAtStrengthTen)).isTrue();
    }

    @Test
    void newlyEncodedPassword_usesStrengthTwelve() {
        String hash = encoder.encode("some-new-password");

        // BCrypt hash format: $<version>$<cost>$<22-char-salt><31-char-hash>
        String cost = hash.split("\\$")[2];
        assertThat(cost).isEqualTo("12");
    }
}

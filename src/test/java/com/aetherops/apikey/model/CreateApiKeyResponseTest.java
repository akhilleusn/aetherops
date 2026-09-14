package com.aetherops.apikey.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 3 finding 6 (see STEP_REPORT.md): the one-time plaintext API key must never appear in toString(). */
class CreateApiKeyResponseTest {

    @Test
    void toString_redactsPlaintext_keepsPrefixVisible() {
        CreateApiKeyResponse response = CreateApiKeyResponse.builder()
            .id(UUID.randomUUID())
            .projectId(UUID.randomUUID())
            .keyPrefix("ak_XXXXXXXXX")
            .plaintext("ak_super-secret-full-key-value")
            .description("CI key")
            .createdAt(Instant.now())
            .build();

        String s = response.toString();

        assertThat(s).contains("ak_XXXXXXXXX").doesNotContain("ak_super-secret-full-key-value");
    }
}

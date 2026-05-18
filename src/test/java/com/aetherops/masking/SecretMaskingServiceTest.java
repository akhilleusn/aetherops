package com.aetherops.masking;

import com.aetherops.masking.service.SecretMaskingService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SecretMaskingServiceTest {

    private final SecretMaskingService service = new SecretMaskingService();

    @Test
    void masksBearerToken() {
        String input  = "Authorization: Bearer eyJhbGciOiJSUzI1NiJ9.payload.signature";
        String result = service.mask(input);

        assertThat(result).isEqualTo("Authorization: Bearer [REDACTED]");
    }

    @Test
    void masksBasicCredentials() {
        String input  = "Authorization: Basic dXNlcjpwYXNzd29yZA==";
        String result = service.mask(input);

        assertThat(result).isEqualTo("Authorization: Basic [REDACTED]");
    }

    @Test
    void masksJsonPassword() {
        String input  = "{\"username\":\"alice\",\"password\":\"s3cr3t!\"}";
        String result = service.mask(input);

        assertThat(result).isEqualTo("{\"username\":\"alice\",\"password\":\"[REDACTED]\"}");
    }

    @Test
    void masksJsonApiKey() {
        String input  = "{\"api_key\":\"sk-abcdef123456\",\"model\":\"gpt-4\"}";
        String result = service.mask(input);

        assertThat(result).isEqualTo("{\"api_key\":\"[REDACTED]\",\"model\":\"gpt-4\"}");
    }

    @Test
    void masksKeyEqualsValuePassword() {
        String input  = "Login failed: password=MySecret123 for user=alice";
        String result = service.mask(input);

        assertThat(result).isEqualTo("Login failed: password=[REDACTED] for user=alice");
    }

    @Test
    void masksKeyColonValueToken() {
        String input  = "Request rejected, token: abc.def.ghi";
        String result = service.mask(input);

        assertThat(result).isEqualTo("Request rejected, token: [REDACTED]");
    }

    @Test
    void masksJdbcUsernameAndPassword() {
        String input  = "Connection error: jdbc:postgresql://dbuser:dbpass123@localhost:5432/mydb";
        String result = service.mask(input);

        assertThat(result).isEqualTo(
            "Connection error: jdbc:postgresql://[REDACTED]:[REDACTED]@localhost:5432/mydb");
    }

    @Test
    void returnsNullWhenInputIsNull() {
        assertThat(service.mask(null)).isNull();
    }

    @Test
    void returnsBlankInputUnchanged() {
        assertThat(service.mask("")).isEqualTo("");
        assertThat(service.mask("   ")).isEqualTo("   ");
    }

    @Test
    void doesNotModifyNormalSafeLogMessage() {
        String input = "User alice logged in from 192.168.1.1 at 2026-05-18T01:00:00Z";

        assertThat(service.mask(input)).isEqualTo(input);
    }
}

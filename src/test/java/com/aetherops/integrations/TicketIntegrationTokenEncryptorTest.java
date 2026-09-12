package com.aetherops.integrations;

import com.aetherops.integrations.service.TicketIntegrationTokenEncryptionProperties;
import com.aetherops.integrations.service.TicketIntegrationTokenEncryptor;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TicketIntegrationTokenEncryptorTest {

    private final TicketIntegrationTokenEncryptor encryptor =
        new TicketIntegrationTokenEncryptor(props("01234567890123456789012345678901"));

    private static TicketIntegrationTokenEncryptionProperties props(String currentKey) {
        TicketIntegrationTokenEncryptionProperties properties = new TicketIntegrationTokenEncryptionProperties();
        properties.setCurrent(1);
        properties.setKeys(Map.of(1, currentKey));
        return properties;
    }

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        String token = "jira-api-token-abc123";
        assertThat(encryptor.decrypt(encryptor.encrypt(token))).isEqualTo(token);
    }

    @Test
    void twoEncrypts_sameInput_differentCiphertext() {
        String token = "servicenow-api-token-xyz";
        String first = encryptor.encrypt(token);
        String second = encryptor.encrypt(token);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void constructor_rejectsKeyOfWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new TicketIntegrationTokenEncryptor(props("too-short")));
    }

    /**
     * app.integrations.token-encryption has no default (see the class-level Javadoc) —
     * the app must fail to start rather than silently encrypt Jira/ServiceNow tokens with a
     * key shipped in source control, matching SlackTokenEncryptor's required-env-var pattern.
     */
    @Test
    void constructor_missingKey_throwsRatherThanUsingAHardcodedDefault() {
        assertThrows(IllegalArgumentException.class, () -> new TicketIntegrationTokenEncryptor(props("")));
    }
}

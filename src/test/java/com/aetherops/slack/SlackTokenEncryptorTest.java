package com.aetherops.slack;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTokenEncryptorTest {

    private final SlackTokenEncryptor encryptor =
        new SlackTokenEncryptor(props("01234567890123456789012345678901"));

    private static SlackTokenEncryptionProperties props(String currentKey) {
        SlackTokenEncryptionProperties properties = new SlackTokenEncryptionProperties();
        properties.setCurrent(1);
        properties.setKeys(Map.of(1, currentKey));
        return properties;
    }

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        String token = "xoxb-1234567890-abcdefghijklmnop";
        assertThat(encryptor.decrypt(encryptor.encrypt(token))).isEqualTo(token);
    }

    @Test
    void twoEncrypts_sameInput_differentCiphertext() {
        String token = "xoxb-1234567890-abcdefghijklmnop";
        String first  = encryptor.encrypt(token);
        String second = encryptor.encrypt(token);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void constructor_rejectsKeyOfWrongLength() {
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new SlackTokenEncryptor(props("too-short")));
    }
}

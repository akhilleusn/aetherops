package com.aetherops.slack;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackTokenEncryptorTest {

    private final SlackTokenEncryptor encryptor =
        new SlackTokenEncryptor("01234567890123456789012345678901");

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
            () -> new SlackTokenEncryptor("too-short"));
    }
}

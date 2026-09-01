package com.aetherops.calendar;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalendarTokenEncryptorTest {

    private final CalendarTokenEncryptor encryptor =
        new CalendarTokenEncryptor("01234567890123456789012345678901");

    @Test
    void encryptDecrypt_roundTrip_returnsOriginal() {
        String token = "1//refresh-token-abc123";
        assertThat(encryptor.decrypt(encryptor.encrypt(token))).isEqualTo(token);
    }

    @Test
    void twoEncrypts_sameInput_differentCiphertext() {
        String token = "1//refresh-token-abc123";
        String first = encryptor.encrypt(token);
        String second = encryptor.encrypt(token);
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void constructor_rejectsKeyOfWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarTokenEncryptor("too-short"));
    }

    @Test
    void constructor_defaultKey_isValidThirtyTwoBytes() {
        assertThat(new CalendarTokenEncryptor("000000000000000000000000000000AB")).isNotNull();
    }
}

package com.aetherops.calendar;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CalendarTokenEncryptorTest {

    private final CalendarTokenEncryptor encryptor =
        new CalendarTokenEncryptor(props("01234567890123456789012345678901"));

    private static CalendarTokenEncryptionProperties props(String currentKey) {
        CalendarTokenEncryptionProperties properties = new CalendarTokenEncryptionProperties();
        properties.setCurrent(1);
        properties.setKeys(Map.of(1, currentKey));
        return properties;
    }

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
        assertThrows(IllegalArgumentException.class, () -> new CalendarTokenEncryptor(props("too-short")));
    }

    @Test
    void constructor_anyThirtyTwoByteKey_constructsSuccessfully() {
        assertThat(new CalendarTokenEncryptor(props("000000000000000000000000000000AB"))).isNotNull();
    }

    /**
     * app.calendar.token-encryption has no default (see the class-level Javadoc) — the
     * app must fail to start rather than silently encrypt with a key shipped in source
     * control, matching SlackTokenEncryptor's required-env-var-only pattern.
     */
    @Test
    void constructor_missingKey_throwsRatherThanUsingAHardcodedDefault() {
        assertThrows(IllegalArgumentException.class, () -> new CalendarTokenEncryptor(props("")));
    }
}

package com.aetherops.calendar;

import com.aetherops.crypto.VersionedAesGcmEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Same versioned AES/GCM scheme as {@code SlackTokenEncryptor}. No default key — like
 * app.slack.token-encryption, this is secret master key material for third-party OAuth refresh
 * tokens and must be supplied via APP_CALENDAR_TOKEN_ENCRYPTION_KEYS_* env vars; the
 * application intentionally fails to start rather than silently encrypting with a key that
 * ships in source control.
 */
@Component
@Slf4j
public class CalendarTokenEncryptor {

    private static final String PURPOSE = "calendar refresh token";

    private final VersionedAesGcmEncryptor core;

    public CalendarTokenEncryptor(CalendarTokenEncryptionProperties properties) {
        Map<Integer, SecretKeySpec> keys = new HashMap<>();
        properties.getKeys().forEach((version, key) ->
            keys.put(version, VersionedAesGcmEncryptor.buildKeySpec(key, version, PURPOSE)));
        keys.putIfAbsent(VersionedAesGcmEncryptor.LEGACY_VERSION, keys.get(properties.getCurrent()));

        this.core = new VersionedAesGcmEncryptor(keys, properties.getCurrent(), PURPOSE);
    }

    public String encrypt(String plaintext) {
        return core.encrypt(plaintext);
    }

    public String decrypt(String encoded) {
        return core.decrypt(encoded);
    }
}

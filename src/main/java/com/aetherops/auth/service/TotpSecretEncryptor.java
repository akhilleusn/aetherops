package com.aetherops.auth.service;

import com.aetherops.crypto.VersionedAesGcmEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class TotpSecretEncryptor {

    private static final String PURPOSE = "TOTP secret";
    private static final String ENV_VAR_PREFIX = "APP_TOTP_ENCRYPTION_KEYS_";
    private static final String INSECURE_DEFAULT = "00000000000000000000000000000000";

    private final VersionedAesGcmEncryptor core;

    public TotpSecretEncryptor(TotpEncryptionProperties properties) {
        if (INSECURE_DEFAULT.equals(properties.getKeys().get(properties.getCurrent()))) {
            log.warn("TOTP encryption key is using the insecure default. Set APP_TOTP_ENCRYPTION_KEYS_{} in production.",
                properties.getCurrent());
        }

        Map<Integer, SecretKeySpec> keys = new HashMap<>();
        properties.getKeys().forEach((version, key) ->
            keys.put(version, VersionedAesGcmEncryptor.buildKeySpec(key, ENV_VAR_PREFIX + version)));
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

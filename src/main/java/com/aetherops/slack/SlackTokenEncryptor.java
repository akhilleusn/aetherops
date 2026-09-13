package com.aetherops.slack;

import com.aetherops.crypto.VersionedAesGcmEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

@Component
@Slf4j
public class SlackTokenEncryptor {

    private static final String PURPOSE = "Slack bot token";
    private static final String ENV_VAR_PREFIX = "APP_SLACK_TOKEN_ENCRYPTION_KEYS_";

    private final VersionedAesGcmEncryptor core;

    public SlackTokenEncryptor(SlackTokenEncryptionProperties properties) {
        Map<Integer, SecretKeySpec> keys = new HashMap<>();
        properties.getKeys().forEach((version, key) ->
            keys.put(version, VersionedAesGcmEncryptor.buildKeySpec(key, ENV_VAR_PREFIX + version)));
        // Before this class supported versioning, everything was encrypted under whatever key
        // is configured as current -- so unless a rotation has already given version 0 its own
        // entry, the current key IS the legacy key.
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

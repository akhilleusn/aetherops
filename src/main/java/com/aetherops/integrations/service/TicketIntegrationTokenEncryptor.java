package com.aetherops.integrations.service;

import com.aetherops.crypto.VersionedAesGcmEncryptor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.spec.SecretKeySpec;
import java.util.HashMap;
import java.util.Map;

/**
 * Versioned AES/GCM encryption for stored Jira/ServiceNow API tokens, same scheme as
 * com.aetherops.slack.SlackTokenEncryptor. No default key — this is secret master key material
 * and must be supplied via APP_INTEGRATIONS_TOKEN_ENCRYPTION_KEYS_* env vars; the application
 * intentionally fails to start rather than silently encrypting with a key that ships in source
 * control.
 *
 * <p><b>Also used for inbound webhook signing secrets:</b> {@code WebhookSigningSecretService}
 * reuses this class (and therefore this key/version space) for a different trust domain —
 * inbound GitHub/PagerDuty/Grafana webhook signing secrets, not Jira/ServiceNow tokens.
 * Rotating a version here rotates both at once. See
 * {@link TicketIntegrationTokenEncryptionProperties} and STEP_REPORT.md.
 */
@Component
@Slf4j
public class TicketIntegrationTokenEncryptor {

    private static final String PURPOSE = "ticket integration token";
    private static final String ENV_VAR_PREFIX = "APP_INTEGRATIONS_TOKEN_ENCRYPTION_KEYS_";

    private final VersionedAesGcmEncryptor core;

    public TicketIntegrationTokenEncryptor(TicketIntegrationTokenEncryptionProperties properties) {
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

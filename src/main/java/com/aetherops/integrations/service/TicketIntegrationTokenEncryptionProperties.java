package com.aetherops.integrations.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key material for {@link TicketIntegrationTokenEncryptor}, keyed by version number so rotation
 * doesn't orphan existing ciphertext (Phase 3 finding 1, see STEP_REPORT.md). Bound from
 * environment variables only, never defaulted in a committed file, e.g.:
 * <pre>
 *   APP_INTEGRATIONS_TOKEN_ENCRYPTION_CURRENT=1
 *   APP_INTEGRATIONS_TOKEN_ENCRYPTION_KEYS_1=&lt;32-byte secret&gt;
 * </pre>
 *
 * <p><b>Coupling with inbound webhook signing secrets:</b> {@code WebhookSigningSecretService}
 * reuses this same encryptor (and therefore this same key/version space) for inbound
 * GitHub/PagerDuty/Grafana webhook signing secrets — a different trust domain from Jira/
 * ServiceNow API tokens. Rotating a version here rotates both simultaneously; there is no way
 * to give the two their own independent version numbering without giving them independent key
 * material (i.e. actually splitting the encryptor), which is out of scope for this change. See
 * STEP_REPORT.md for why that split was deliberately deferred.
 */
@Configuration
@ConfigurationProperties(prefix = "app.integrations.token.encryption")
public class TicketIntegrationTokenEncryptionProperties {

    private int current = 1;
    private Map<Integer, String> keys = new LinkedHashMap<>();

    public int getCurrent() { return current; }
    public void setCurrent(int current) { this.current = current; }
    public Map<Integer, String> getKeys() { return keys; }
    public void setKeys(Map<Integer, String> keys) { this.keys = keys; }
}

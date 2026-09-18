package com.aetherops.slack;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key material for {@link SlackTokenEncryptor}, keyed by version number so rotation doesn't
 * orphan existing ciphertext (Phase 3 finding 1, see STEP_REPORT.md). Bound from environment
 * variables only, never defaulted in a committed file, e.g.:
 * <pre>
 *   APP_SLACK_TOKEN_ENCRYPTION_CURRENT=1
 *   APP_SLACK_TOKEN_ENCRYPTION_KEYS_1=&lt;32-byte secret&gt;
 * </pre>
 * On a future rotation: add {@code APP_SLACK_TOKEN_ENCRYPTION_KEYS_2} with the new secret and
 * bump {@code CURRENT} to 2, while leaving {@code KEYS_1} (and {@code KEYS_0}, if set) in place
 * so ciphertext encrypted under the old key keeps decrypting.
 */
@Configuration
@ConfigurationProperties(prefix = "app.slack.token.encryption")
public class SlackTokenEncryptionProperties {

    private int current = 1;
    private Map<Integer, String> keys = new LinkedHashMap<>();

    public int getCurrent() { return current; }
    public void setCurrent(int current) { this.current = current; }
    public Map<Integer, String> getKeys() { return keys; }
    public void setKeys(Map<Integer, String> keys) { this.keys = keys; }
}

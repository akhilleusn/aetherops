package com.aetherops.calendar;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key material for {@link CalendarTokenEncryptor}, keyed by version number so rotation doesn't
 * orphan existing ciphertext (Phase 3 finding 1, see STEP_REPORT.md). Bound from environment
 * variables only, never defaulted in a committed file, e.g.:
 * <pre>
 *   APP_CALENDAR_TOKEN_ENCRYPTION_CURRENT=1
 *   APP_CALENDAR_TOKEN_ENCRYPTION_KEYS_1=&lt;32-byte secret&gt;
 * </pre>
 */
@Configuration
@ConfigurationProperties(prefix = "app.calendar.token.encryption")
public class CalendarTokenEncryptionProperties {

    private int current = 1;
    private Map<Integer, String> keys = new LinkedHashMap<>();

    public int getCurrent() { return current; }
    public void setCurrent(int current) { this.current = current; }
    public Map<Integer, String> getKeys() { return keys; }
    public void setKeys(Map<Integer, String> keys) { this.keys = keys; }
}

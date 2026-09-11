package com.aetherops.integration.twilio;

import com.twilio.Twilio;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Step G1 (see STEP_REPORT.md): {@code Twilio.init(accountSid, authToken)} was never called
 * anywhere in this codebase -- every {@code Message.creator(...).create()} call in
 * {@link com.aetherops.oncall.OnCallService} threw {@code AuthenticationException} before any
 * network call was even attempted, caught, logged, and silently treated as a successful page.
 * Mirrors {@code StripeConfig}'s existing, working pattern: initialize the SDK once at startup
 * if credentials are present, otherwise log loudly and leave the feature disabled rather than
 * crash the whole app over one optional integration.
 */
@Configuration
@ConfigurationProperties(prefix = "twilio")
@Slf4j
public class TwilioConfig {
    private String accountSid;
    private String authToken;
    private String fromNumber;

    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("Twilio credentials not configured (TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN) -- " +
                "SMS paging is disabled; any page attempt will be recorded as a delivery failure, " +
                "not silently treated as success");
            return;
        }
        Twilio.init(accountSid, authToken);
        log.info("Twilio SDK initialized");
    }

    public boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank() && authToken != null && !authToken.isBlank();
    }

    public String getAccountSid() { return accountSid; }
    public void setAccountSid(String accountSid) { this.accountSid = accountSid; }
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }
    public String getFromNumber() { return fromNumber; }
    public void setFromNumber(String fromNumber) { this.fromNumber = fromNumber; }
}

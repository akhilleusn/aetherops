package com.aetherops.integration.twilio;

import com.twilio.Twilio;
import com.twilio.http.NetworkHttpClient;
import com.twilio.http.TwilioRestClient;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.SocketConfig;
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
 *
 * <p>Step H2 (see STEP_REPORT.md): the Twilio Java SDK is NOT built on Spring's
 * {@code RestClient} (nothing in this codebase's HTTP-client timeout work reaches it) -- it has
 * its own internal Apache HttpClient 4 (`com.twilio.http.NetworkHttpClient`), whose *default*
 * timeouts (decompiled from {@code com.twilio.http.HttpClient}'s static initializer in
 * twilio-9.0.0.jar to confirm, rather than assume) are connect=10,000ms / socket(read)=30,500ms.
 * That default is bounded, but not short: paging is the one call where a slow success is close
 * to useless -- {@code EscalationService}'s SMS→push→Slack fallback should already have moved
 * on to push well before 30.5s. Tightened explicitly below rather than left as an inherited SDK
 * default, per instruction -- an unstated default is exactly the kind of thing that changes out
 * from under this app on a future Twilio SDK upgrade with no changelog entry anyone reads.
 * {@code app.twilio.connect-timeout-ms}/{@code read-timeout-ms} default to 3000/5000: Twilio's
 * documented p99 API latency is well under a second, so this leaves several times that as
 * headroom while still failing fast enough (worst case ~8s) that the paging bulkhead's small
 * pool isn't tied up and the escalation fallback chain isn't meaningfully delayed.
 */
@Configuration
@ConfigurationProperties(prefix = "twilio")
@Slf4j
public class TwilioConfig {
    private String accountSid;
    private String authToken;
    private String fromNumber;
    private long connectTimeoutMs = 3000;
    private long readTimeoutMs = 5000;

    @PostConstruct
    public void init() {
        if (!isConfigured()) {
            log.warn("Twilio credentials not configured (TWILIO_ACCOUNT_SID/TWILIO_AUTH_TOKEN) -- " +
                "SMS paging is disabled; any page attempt will be recorded as a delivery failure, " +
                "not silently treated as success");
            return;
        }
        Twilio.init(accountSid, authToken);

        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout((int) connectTimeoutMs)
            .setSocketTimeout((int) readTimeoutMs)
            .build();
        SocketConfig socketConfig = SocketConfig.custom()
            .setSoTimeout((int) readTimeoutMs)
            .build();
        TwilioRestClient restClient = new TwilioRestClient.Builder(accountSid, authToken)
            .httpClient(new NetworkHttpClient(requestConfig, socketConfig))
            .build();
        Twilio.setRestClient(restClient);

        log.info("Twilio SDK initialized with connectTimeoutMs={} readTimeoutMs={} (SDK default is 10000/30500)",
            connectTimeoutMs, readTimeoutMs);
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
    public long getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(long connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public long getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(long readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
}

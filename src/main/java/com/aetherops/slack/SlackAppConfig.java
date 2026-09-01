package com.aetherops.slack;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Getter
public class SlackAppConfig {

    private final String clientId;
    private final String clientSecret;
    private final String signingSecret;
    private final String redirectUri;

    public SlackAppConfig(
            @Value("${app.slack.client-id:}") String clientId,
            @Value("${app.slack.client-secret:}") String clientSecret,
            @Value("${app.slack.signing-secret:}") String signingSecret,
            @Value("${app.slack.redirect-uri:http://localhost:8080/slack/oauth/callback}") String redirectUri) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.signingSecret = signingSecret;
        this.redirectUri = redirectUri;
    }

    public boolean isConfigured() {
        return !clientId.isBlank() && !clientSecret.isBlank() && !signingSecret.isBlank();
    }
}

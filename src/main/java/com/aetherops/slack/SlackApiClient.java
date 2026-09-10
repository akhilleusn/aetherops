package com.aetherops.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class SlackApiClient {

    private final SlackAppConfig config;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    public record OAuthResult(String accessToken, String botUserId, String teamId, String teamName,
                               String scope, String defaultChannelId) {}

    public OAuthResult exchangeCode(String code) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", config.getClientId());
        form.add("client_secret", config.getClientSecret());
        form.add("code", code);
        form.add("redirect_uri", config.getRedirectUri());

        String body = restClientBuilder.build().post()
                .uri("https://slack.com/api/oauth.v2.access")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(String.class);

        JsonNode json;
        try {
            json = objectMapper.readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Slack OAuth response", e);
        }

        if (!json.path("ok").asBoolean(false)) {
            throw new IllegalStateException("Slack OAuth exchange failed: " + json.path("error").asText("unknown_error"));
        }

        JsonNode incomingWebhook = json.path("incoming_webhook");
        String channelId = incomingWebhook.has("channel_id") ? incomingWebhook.path("channel_id").asText() : null;

        return new OAuthResult(
                json.path("access_token").asText(),
                json.path("bot_user_id").asText(),
                json.path("team").path("id").asText(),
                json.path("team").path("name").asText(),
                json.path("scope").asText(),
                channelId);
    }

    /**
     * Phase 2 remediation, finding 11 (see STEP_REPORT.md): {@code SlackSignatureFilter}
     * already verifies this payload came from Slack before this method is ever called, so
     * {@code responseUrl} should always genuinely be Slack's own. This check is defense in
     * depth, not the primary control — the same reasoning {@link
     * com.aetherops.integrations.webhook.AwsSnsHostValidator} exists for SNS: a second,
     * independent check means a future weakening of the signature check doesn't turn this
     * into an open SSRF sink with nothing else standing in the way.
     */
    private static boolean isHooksSlackComUrl(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme())
                && "hooks.slack.com".equalsIgnoreCase(uri.getHost() != null ? uri.getHost().toLowerCase(Locale.ROOT) : null);
        } catch (Exception e) {
            return false;
        }
    }

    public void respondToInteraction(String responseUrl, String text) {
        if (!isHooksSlackComUrl(responseUrl)) {
            log.warn("Refusing to post Slack interaction response — response_url is not a genuine hooks.slack.com URL");
            return;
        }
        try {
            restClientBuilder.build().post()
                    .uri(responseUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", text, "replace_original", false))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failed to post Slack interaction response", e);
        }
    }
}

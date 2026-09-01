package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bot-token Slack Web API calls — distinct from {@link SlackNotifier}'s incoming-webhook
 * path. Used when a tenant has completed the interactive OAuth install (Phase 2), so we
 * have a bot token and a target channel and can capture the posted message's channel/ts
 * for later thread-transcript retrieval (used by postmortem generation).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlackConversationService {

    private final SlackTokenEncryptor tokenEncryptor;
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    public record PostedMessage(String channel, String ts) {}

    public Optional<PostedMessage> postIncidentAlert(SlackWorkspace workspace, Incident incident) {
        if (workspace.getDefaultChannelId() == null) {
            return Optional.empty();
        }

        String emoji = incident.getSeverity() == IncidentSeverity.CRITICAL ? "🚨" : "⚠️";
        String text = emoji + " " + incident.getSeverity() + " incident on `" + incident.getServiceId()
            + "` — " + incident.getTitle();

        return postMessage(workspace, workspace.getDefaultChannelId(), text);
    }

    /** Posts arbitrary text to a channel — the general-purpose primitive {@link #postIncidentAlert} builds on. */
    public Optional<PostedMessage> postMessage(SlackWorkspace workspace, String channelId, String text) {
        String token = tokenEncryptor.decrypt(workspace.getEncryptedBotToken());

        try {
            String body = restClientBuilder.build().post()
                .uri("https://slack.com/api/chat.postMessage")
                .headers(h -> h.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("channel", channelId, "text", text))
                .retrieve()
                .body(String.class);

            JsonNode json = objectMapper.readTree(body);
            if (!json.path("ok").asBoolean(false)) {
                log.warn("chat.postMessage failed tenantId={} error={}",
                    workspace.getTenantId(), json.path("error").asText("unknown_error"));
                return Optional.empty();
            }

            return Optional.of(new PostedMessage(json.path("channel").asText(), json.path("ts").asText()));
        } catch (Exception e) {
            log.warn("chat.postMessage failed tenantId={} channelId={}", workspace.getTenantId(), channelId, e);
            return Optional.empty();
        }
    }

    /** Returns transcript lines (message text only); empty on any failure — this is best-effort enrichment. */
    public List<String> fetchThreadTranscript(SlackWorkspace workspace, String channel, String threadTs) {
        String token = tokenEncryptor.decrypt(workspace.getEncryptedBotToken());
        List<String> lines = new ArrayList<>();

        try {
            String uri = "https://slack.com/api/conversations.replies?channel="
                + java.net.URLEncoder.encode(channel, java.nio.charset.StandardCharsets.UTF_8)
                + "&ts=" + java.net.URLEncoder.encode(threadTs, java.nio.charset.StandardCharsets.UTF_8);

            String body = restClientBuilder.build().get()
                .uri(uri)
                .headers(h -> h.setBearerAuth(token))
                .retrieve()
                .body(String.class);

            JsonNode json = objectMapper.readTree(body);
            if (!json.path("ok").asBoolean(false)) {
                log.warn("conversations.replies failed channel={} error={}",
                    channel, json.path("error").asText("unknown_error"));
                return List.of();
            }

            for (JsonNode message : json.path("messages")) {
                String text = message.path("text").asText("");
                if (!text.isBlank()) {
                    lines.add(text);
                }
            }
        } catch (Exception e) {
            log.warn("conversations.replies failed channel={} threadTs={}", channel, threadTs, e);
            return List.of();
        }

        return lines;
    }
}

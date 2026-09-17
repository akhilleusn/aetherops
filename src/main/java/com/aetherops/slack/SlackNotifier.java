package com.aetherops.slack;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.springframework.http.MediaType.APPLICATION_JSON;

@Service
@Slf4j
public class SlackNotifier {

    private final String webhookUrl;
    private final String dashboardBaseUrl;
    private final RestClient restClient;

    @Autowired
    public SlackNotifier(
        @Value("${app.slack.webhook-url:}") String webhookUrl,
        @Value("${app.dashboard-base-url:http://localhost:8080}") String dashboardBaseUrl,
        @Value("${app.http-client.default-connect-timeout-ms}") long connectTimeoutMs,
        @Value("${app.http-client.default-read-timeout-ms}") long readTimeoutMs
    ) {
        this(webhookUrl, dashboardBaseUrl, buildDefaultRestClient(connectTimeoutMs, readTimeoutMs));
    }

    SlackNotifier(String webhookUrl, String dashboardBaseUrl, RestClient restClient) {
        this.webhookUrl = webhookUrl;
        this.dashboardBaseUrl = dashboardBaseUrl;
        this.restClient = restClient;
    }

    @Async("slackExecutor")
    public void notify(Incident incident) {
        if (webhookUrl.isBlank()) {
            return;
        }
        try {
            restClient.post()
                .uri(webhookUrl)
                .contentType(APPLICATION_JSON)
                .body(buildPayload(incident))
                .retrieve()
                .toBodilessEntity();
            log.info("Slack alert sent incidentId={} severity={}", incident.getId(), incident.getSeverity());
        } catch (Exception e) {
            log.warn("Slack notification failed incidentId={}", incident.getId(), e);
        }
    }

    String buildPayload(Incident incident) {
        String emoji       = incident.getSeverity() == IncidentSeverity.CRITICAL ? "🚨" : "⚠️";
        String severity    = incident.getSeverity().name();
        String title       = escape(incident.getTitle());
        String serviceId   = escape(incident.getServiceId());
        String environment = incident.getEnvironment() != null ? escape(incident.getEnvironment()) : "—";
        String link        = dashboardBaseUrl + "/incidents/" + incident.getId();
        int    eventCount  = incident.getEventCount();

        return """
            {
              "text": "%s %s incident on `%s` — %s",
              "blocks": [
                {
                  "type": "header",
                  "text": { "type": "plain_text", "text": "%s %s Incident Alert" }
                },
                {
                  "type": "section",
                  "text": { "type": "mrkdwn", "text": "*%s*" }
                },
                {
                  "type": "section",
                  "fields": [
                    { "type": "mrkdwn", "text": "*Severity:*\\n%s" },
                    { "type": "mrkdwn", "text": "*Service:*\\n`%s`" },
                    { "type": "mrkdwn", "text": "*Events:*\\n%d" },
                    { "type": "mrkdwn", "text": "*Environment:*\\n%s" }
                  ]
                },
                {
                  "type": "actions",
                  "elements": [
                    {
                      "type": "button",
                      "text": { "type": "plain_text", "text": "View Incident" },
                      "url": "%s"
                    }
                  ]
                }
              ]
            }""".formatted(
                emoji, severity, serviceId, title,
                emoji, severity,
                title,
                severity, serviceId, eventCount, environment,
                link);
    }

    private static String escape(String text) {
        if (text == null) return "";
        return text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    private static RestClient buildDefaultRestClient(long connectTimeoutMs, long readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
        factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
        return RestClient.builder().requestFactory(factory).build();
    }
}
